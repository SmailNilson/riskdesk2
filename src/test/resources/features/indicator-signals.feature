Feature: Indicator Signal Detection
  As a technical trader
  I want alerts on indicator signals

  Scenario: EMA Golden Cross alert
    Given an indicator snapshot with EMA crossover GOLDEN_CROSS for MCL on 10m
    When indicator alerts are evaluated
    Then an INFO alert should be generated for EMA with message containing "Golden Cross"

  Scenario: RSI Overbought alert
    Given an indicator snapshot with RSI signal OVERBOUGHT and RSI value 72.5 for MCL on 10m
    When indicator alerts are evaluated
    Then an INFO alert should be generated for RSI with message containing "overbought"

  Scenario: MACD Bearish Cross alert
    Given an indicator snapshot with MACD crossover BEARISH_CROSS for E6 on 1h
    When indicator alerts are evaluated
    Then an INFO alert should be generated for MACD with message containing "Bearish Cross"

  Scenario: SMC CHoCH detection
    Given an indicator snapshot with break type CHOCH_BULLISH for MNQ on 10m
    When indicator alerts are evaluated
    Then a WARNING alert should be generated for SMC with message containing "CHoCH"
