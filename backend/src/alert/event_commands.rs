//! Read-only event-ID lookup for the administrator's FCM test workflow.
use super::{bot::BotState, telegram::escape};
use crate::{error::AppError, repository::EventRepository};

pub(super) async fn list(state: &BotState, text: &str) -> Result<String, AppError> {
    let keyword = text
        .split_once(char::is_whitespace)
        .map(|(_, argument)| argument.trim())
        .unwrap_or_default();
    if keyword.chars().count() > 100 {
        return Ok("关键词最多 100 个字符。用法：/events [事件名、国家或货币关键词]".into());
    }
    let events = EventRepository::new(state.pool.clone())
        .search_for_admin(keyword, chrono::Utc::now())
        .await?;
    if events.is_empty() {
        return Ok(if keyword.is_empty() {
            "前后 7 天暂无已缓存事件。可用 /events 关键词 搜索全部已缓存事件；请检查日历同步状态。"
        } else {
            "未找到匹配的已缓存事件。可尝试英文名、简体/繁体译名、国家或货币（例如 /events CPI）。"
        }
        .into());
    }

    let mut reply = if keyword.is_empty() {
        "<b>事件 ID 查询</b>（前后 7 天，按距现在最近排序；时间为 UTC）\n".to_owned()
    } else {
        "<b>事件 ID 查询</b>（关键词匹配全部已缓存事件，按距现在最近排序；时间为 UTC）\n".to_owned()
    };
    let short = |value: &str| {
        let mut chars = value.chars();
        let mut result: String = chars.by_ref().take(48).collect();
        if chars.next().is_some() {
            result.push('…');
        }
        escape(&result)
    };
    let mut displayed = 0;
    for event in events.iter().take(10) {
        let name = event
            .event_zh_cn
            .as_deref()
            .filter(|name| !name.trim().is_empty())
            .or(event
                .event_zh_tw
                .as_deref()
                .filter(|name| !name.trim().is_empty()));
        let name = match name {
            Some(name) if name != event.event => {
                format!("{} / {}", short(name), short(&event.event))
            }
            _ => short(&event.event),
        };
        let time = if event.time_exact {
            event.event_time.format("%Y-%m-%d %H:%M").to_string()
        } else {
            format!("{}（具体时间待定）", event.event_time.format("%Y-%m-%d"))
        };
        let row = format!(
            "\nID：<code>{}</code> · {}\n{}\n{} UTC · {}\n<code>/test_fcm {}</code>\n",
            event.id,
            short(&event.country),
            name,
            time,
            event.status,
            event.id,
        );
        // Bound even the encoded HTML/UTF-16 form, leaving room for instructions below.
        if reply.encode_utf16().count() + row.encode_utf16().count() > 3500 {
            break;
        }
        reply.push_str(&row);
        displayed += 1;
    }
    if displayed < events.len() {
        reply.push_str(
            "\n结果较多，仅显示部分（最多 10 条），请用 /events 更具体的关键词 缩小范围。\n",
        );
    }
    reply.push_str("\n以上为当前后端事件 ID。先在客户端后端模式关注对应事件，再复制上方测试命令；测试会发给所有关注者。查询本身不会推送。");
    Ok(reply)
}
