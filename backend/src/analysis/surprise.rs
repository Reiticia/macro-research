use rust_decimal::Decimal;

pub fn raw_surprise(actual: Option<Decimal>, consensus: Option<Decimal>) -> Option<Decimal> {
    Some(actual? - consensus?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn surprise_uses_consensus_not_previous() {
        assert_eq!(
            raw_surprise(Some(Decimal::new(32, 1)), Some(Decimal::new(29, 1))),
            Some(Decimal::new(3, 1)),
        );
        assert_eq!(raw_surprise(Some(Decimal::ONE), None), None);
    }
}
