Feature: Playbook auto-simulation and Auto-IBKR routing
  As a futures trader
  I want eligible playbook plans to be simulated before any optional broker route
  So that Auto-IBKR remains explicit, diagnosable, and safe by default

  Scenario: Eligible playbook starts a simulation while Auto-IBKR is off
    Given playbook auto-simulation is enabled
    And playbook Auto-IBKR is disabled
    And the playbook verdict is actionable with complete entry risk and reward
    When the playbook routing policy is evaluated
    Then a playbook simulation should be requested
    And no IBKR order should be requested
    And the routing reason should be "PAPER_ONLY"

  Scenario: Auto-IBKR routes only after simulation and broker gates pass
    Given playbook auto-simulation is enabled
    And playbook Auto-IBKR is enabled
    And the playbook verdict is actionable with complete entry risk and reward
    And broker preflight allows the order
    When the playbook routing policy is evaluated
    Then a playbook simulation should be requested
    And an IBKR order should be requested
    And the routing reason should be "ROUTED"

  Scenario: Disabled IBKR backend keeps the simulation but blocks the live order
    Given playbook auto-simulation is enabled
    And playbook Auto-IBKR is enabled
    And the playbook verdict is actionable with complete entry risk and reward
    And the IBKR backend is disabled
    When the playbook routing policy is evaluated
    Then a playbook simulation should be requested
    And no IBKR order should be requested
    And the routing reason should be "SKIPPED_IBKR_DISABLED"

  Scenario: Non-actionable playbook does not create simulation or broker side effects
    Given playbook auto-simulation is enabled
    And playbook Auto-IBKR is enabled
    And the playbook verdict is not actionable
    When the playbook routing policy is evaluated
    Then no playbook simulation should be requested
    And no IBKR order should be requested
    And the routing reason should be "SKIPPED_NO_PLAN"
