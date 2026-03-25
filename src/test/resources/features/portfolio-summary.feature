Feature: Portfolio Summary
  As a trader
  I want to see my portfolio metrics

  Scenario: Portfolio with open positions
    Given these positions are open:
      | instrument | side  | quantity | entryPrice |
      | MCL        | LONG  | 2        | 62.40      |
      | MGC        | SHORT | 1        | 2040.00    |
    When I request the portfolio summary via API
    Then the response should have 2 open positions
    And the total exposure should be greater than 0

  Scenario: Empty portfolio
    When I request the portfolio summary via API with no positions
    Then the response should have 0 open positions
