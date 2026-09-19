package com.macroresearch

import android.app.Application
import androidx.room.Room
import com.google.gson.Gson
import com.macroresearch.data.AnalysisPreferences
import com.macroresearch.data.BackendDataSource
import com.macroresearch.data.BackendPreferences
import com.macroresearch.data.CountryPreferences
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.DirectDataSource
import com.macroresearch.data.LocalAnalysisEngine
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.MarketPreferences
import com.macroresearch.data.NetworkPreferences
import com.macroresearch.data.PreferencesBackoffStore
import com.macroresearch.data.TranslationPreferences
import com.macroresearch.data.local.MacroDatabase
import com.macroresearch.data.remote.AiAnalysisClient
import com.macroresearch.data.remote.BackendClient
import com.macroresearch.data.remote.BackendSocket
import com.macroresearch.data.remote.DirectMarketClient
import com.macroresearch.data.remote.EconomicCalendarClient
import com.macroresearch.data.remote.TranslationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class MacroApplication : Application() {
    lateinit var repository: MacroRepository
        private set
    lateinit var backendSocket: BackendSocket
        private set
    lateinit var notificationCenter: NotificationCenter
        private set
    private lateinit var backendPreferences: BackendPreferences
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val translationPreferences = TranslationPreferences(this)
        backendPreferences = BackendPreferences(this)

        val gson = Gson()
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val http = OkHttpClient.Builder()
            .cache(Cache(cacheDir.resolve("http"), 20L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "MacroResearch/0.2 (Android; personal research)")
                        .build(),
                )
            }
            .addInterceptor(logging)
            .build()
        val translationHttp = http.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .build()
        // Backend calls may legitimately take longer than a direct provider round trip: the
        // server generates the briefing and reads its own caches first.
        val backendHttp = http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
        val database = Room.databaseBuilder(this, MacroDatabase::class.java, "macro.db")
            .addMigrations(
                MacroDatabase.MIGRATION_1_2,
                MacroDatabase.MIGRATION_2_3,
                MacroDatabase.MIGRATION_3_4,
                MacroDatabase.MIGRATION_4_5,
                MacroDatabase.MIGRATION_5_6,
            )
            .build()
        if (BuildConfig.DEBUG) {
            AiAnalysisClient.responseObserver = { android.util.Log.d("AiAnalysisRaw", it.take(4000)) }
        }
        val networkPreferences = NetworkPreferences(this)
        val analysisPreferences = AnalysisPreferences(this)
        val backendClient = BackendClient(
            client = backendHttp,
            gson = gson,
            baseUrl = { backendPreferences.settings.value.baseUrl },
            token = backendPreferences::token,
        )
        val calendarClient = EconomicCalendarClient(
            http,
            proxy = networkPreferences::proxy,
            backoff = PreferencesBackoffStore(this),
        )
        val marketClient = DirectMarketClient(http, proxy = networkPreferences::proxy)
        val aiAnalysisClient = AiAnalysisClient(translationHttp, gson)
        repository = MacroRepository(
            directSource = DirectDataSource(
                calendarClient = calendarClient,
                marketClient = marketClient,
                aiAnalysisClient = aiAnalysisClient,
                analysisEngine = LocalAnalysisEngine(),
                translationPreferences = translationPreferences,
            ),
            backendSource = BackendDataSource(backendClient),
            calendarClient = calendarClient,
            networkPreferences = networkPreferences,
            analysisPreferences = analysisPreferences,
            backendPreferences = backendPreferences,
            translationClient = TranslationClient(translationHttp, gson),
            aiAnalysisClient = aiAnalysisClient,
            dao = database.eventDao(),
            analysisDao = database.analysisDao(),
            countryPreferences = CountryPreferences(this),
            marketPreferences = MarketPreferences(this),
            translationPreferences = translationPreferences,
        )
        backendSocket = BackendSocket(
            client = backendHttp,
            gson = gson,
            urlProvider = backendClient::webSocketUrl,
            tokenProvider = backendPreferences::token,
        )
        notificationCenter = NotificationCenter(this)
        applicationScope.launch {
            backendSocket.events.collect { event ->
                if (backendPreferences.settings.value.mode == DataSourceMode.BACKEND) {
                    notificationCenter.show(event)
                }
            }
        }
    }

    /** Opens the push channel; a no-op in direct mode, where no server exists. */
    fun startBackendPush() {
        if (backendPreferences.settings.value.mode == DataSourceMode.BACKEND) {
            backendSocket.connect()
        }
    }

    fun stopBackendPush() {
        backendSocket.close()
    }

    /** True when the user selected the self-hosted backend as the data source. */
    fun usesBackend(): Boolean =
        backendPreferences.settings.value.mode == DataSourceMode.BACKEND
}
