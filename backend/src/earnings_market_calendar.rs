//! Modern US equity regular-session openings (NYSE holiday rules, not federal holidays).
//! Local 09:30 is converted using the TARGET date's IANA New York rules, never today's offset.
//! Unannounced closures/halts cannot be predicted by this offline calendar; leave real gaps.
use chrono::{DateTime, Datelike, Duration, NaiveDate, TimeZone, Utc, Weekday};
use chrono_tz::America::New_York;

pub(super) fn opening(date: NaiveDate, session: &str) -> Option<DateTime<Utc>> {
    let mut day = match session {
        "pre_market" => date,
        "after_hours" => date.succ_opt()?,
        _ => return None,
    };
    // Covers modern DST/holiday rules; don't silently guess historical exchange schedules.
    if !(2022..=2099).contains(&day.year()) {
        return None;
    }
    for _ in 0..14 {
        if trading_day(day) {
            return New_York
                .from_local_datetime(&day.and_hms_opt(9, 30, 0)?)
                .single()
                .map(|time| time.with_timezone(&Utc));
        }
        day = day.succ_opt()?;
    }
    None
}

fn day(year: i32, month: u32, day: u32) -> NaiveDate {
    NaiveDate::from_ymd_opt(year, month, day).unwrap()
}
fn nth_weekday(year: i32, month: u32, weekday: Weekday, nth: i64) -> NaiveDate {
    let first = day(year, month, 1);
    let offset =
        (weekday.num_days_from_monday() as i64 - first.weekday().num_days_from_monday() as i64 + 7)
            % 7;
    first + Duration::days(offset + (nth - 1) * 7)
}
fn observed(date: NaiveDate) -> NaiveDate {
    match date.weekday() {
        Weekday::Sat => date - Duration::days(1),
        Weekday::Sun => date + Duration::days(1),
        _ => date,
    }
}
fn easter(year: i32) -> NaiveDate {
    // Gregorian computus (Meeus/Jones/Butcher).
    let a = year % 19;
    let b = year / 100;
    let c = year % 100;
    let d = b / 4;
    let e = b % 4;
    let f = (b + 8) / 25;
    let g = (b - f + 1) / 3;
    let h = (19 * a + b - d - g + 15) % 30;
    let i = c / 4;
    let k = c % 4;
    let l = (32 + 2 * e + 2 * i - h - k) % 7;
    let m = (a + 11 * h + 22 * l) / 451;
    let value = h + l - 7 * m + 114;
    day(year, (value / 31) as u32, (value % 31 + 1) as u32)
}
fn trading_day(date: NaiveDate) -> bool {
    if matches!(date.weekday(), Weekday::Sat | Weekday::Sun) {
        return false;
    }
    let year = date.year();
    let new_year = day(year, 1, 1);
    // NYSE does NOT close Dec 31 when next Jan 1 falls on Saturday.
    let new_year_closed = new_year.weekday() != Weekday::Sat && date == observed(new_year);
    let last_monday_may = nth_weekday(year, 6, Weekday::Mon, 1) - Duration::days(7);
    !new_year_closed
        && ![
            nth_weekday(year, 1, Weekday::Mon, 3),
            nth_weekday(year, 2, Weekday::Mon, 3),
            easter(year) - Duration::days(2),
            last_monday_may,
            observed(day(year, 6, 19)),
            observed(day(year, 7, 4)),
            nth_weekday(year, 9, Weekday::Mon, 1),
            nth_weekday(year, 11, Weekday::Thu, 4),
            observed(day(year, 12, 25)),
            day(2025, 1, 9), // National day of mourning for President Carter.
        ]
        .contains(&date)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn utc(year: i32, month: u32, date: u32, hour: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(year, month, date, hour, 30, 0)
            .unwrap()
    }
    #[test]
    fn target_session_resolves_both_dst_boundaries_and_pre_market_same_day() {
        assert_eq!(
            opening(day(2026, 3, 6), "after_hours"),
            Some(utc(2026, 3, 9, 13))
        );
        assert_eq!(
            opening(day(2026, 10, 30), "after_hours"),
            Some(utc(2026, 11, 2, 14))
        );
        assert_eq!(
            opening(day(2026, 7, 6), "pre_market"),
            Some(utc(2026, 7, 6, 13))
        );
        assert_eq!(
            opening(day(2026, 11, 3), "pre_market"),
            Some(utc(2026, 11, 3, 14))
        );
        assert_eq!(opening(day(2026, 11, 3), "unknown"), None);
    }
    #[test]
    fn holidays_weekends_early_close_and_year_boundary() {
        assert_eq!(
            opening(day(2026, 4, 2), "after_hours"),
            Some(utc(2026, 4, 6, 13))
        );
        assert_eq!(
            opening(day(2026, 7, 2), "after_hours"),
            Some(utc(2026, 7, 6, 13))
        );
        assert_eq!(
            opening(day(2026, 11, 25), "after_hours"),
            Some(utc(2026, 11, 27, 14))
        );
        assert_eq!(
            opening(day(2026, 12, 31), "after_hours"),
            Some(utc(2027, 1, 4, 14))
        );
        assert_eq!(
            opening(day(2027, 12, 30), "after_hours"),
            Some(utc(2027, 12, 31, 14))
        );
        assert_eq!(
            opening(day(2025, 1, 8), "after_hours"),
            Some(utc(2025, 1, 10, 14))
        );
    }
    #[test]
    fn official_2026_full_day_closures_and_non_exchange_federal_holidays() {
        for (month, date) in [
            (1, 1),
            (1, 19),
            (2, 16),
            (4, 3),
            (5, 25),
            (6, 19),
            (7, 3),
            (9, 7),
            (11, 26),
            (12, 25),
        ] {
            assert!(!trading_day(day(2026, month, date)));
        }
        assert!(trading_day(day(2026, 10, 12))); // Columbus Day
        assert!(trading_day(day(2026, 11, 11))); // Veterans Day
        assert!(!trading_day(day(2027, 6, 18))); // Saturday Juneteenth observed
        assert!(!trading_day(day(2027, 7, 5))); // Sunday Independence Day observed
    }
}
