Feature: Position Management
  As a futures trader
  I want to manage trading positions
  So that I can track my trades and P&L

  Scenario: Open a long position
    When I open a LONG position on MCL with 2 contracts at 62.40
    Then the position should be OPEN
    And the unrealized PnL should be 0

  Scenario: Open a short position
    When I open a SHORT position on MGC with 1 contract at 2040.00
    Then the position should be OPEN

  Scenario: Close a position with profit
    Given an open LONG position on MCL at 62.40 with 2 contracts
    When I close the position at 63.40
    Then the position should be CLOSED
    And the realized PnL should be 200.00

  Scenario: Close a short position with profit
    Given an open SHORT position on MCL at 62.40 with 2 contracts
    When I close the position at 61.40
    Then the position should be CLOSED
    And the realized PnL should be 200.00

  Scenario: Update PnL when price changes
    Given an open LONG position on MCL at 62.40 with 3 contracts
    When the market price updates to 62.65 for MCL
    Then the unrealized PnL should be 75.00
