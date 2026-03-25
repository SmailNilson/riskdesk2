Feature: Risk Monitoring
  As a risk-aware trader
  I want risk alerts when thresholds are breached

  Scenario: Margin exceeded triggers danger alert
    Given a portfolio with margin 25000 and exposure exceeding 80 percent
    When risk is evaluated
    Then a DANGER alert should be generated for RISK

  Scenario: Margin warning triggers warning alert
    Given a portfolio with margin 25000 and exposure at 75 percent
    When risk is evaluated
    Then a WARNING alert should be generated for RISK

  Scenario: Safe margin triggers no alert
    Given a portfolio with margin 25000 and exposure at 40 percent
    When risk is evaluated
    Then no risk alerts should be generated

  Scenario: Alert deduplication within cooldown
    Given a DANGER margin alert was already fired
    When the same risk condition is evaluated again within 5 minutes
    Then the alert should be suppressed
