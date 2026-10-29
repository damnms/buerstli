package io.github.damnms.buerstli.domain

/** Things a user can ask a brush to do. New features start here. */
sealed interface BrushCommand {
    data class ChangeModeSettings(val modeIndex: Int, val settings: ModeSettings) : BrushCommand
    data class ChangeBrushingDuration(val seconds: Int) : BrushCommand
    data class SetSwitch(val switch: Switch, val on: Boolean) : BrushCommand

    /** Make this mode the one the brush uses. */
    data class ActivateMode(val modeIndex: Int) : BrushCommand
}

sealed interface CommandResult {
    /** The brush applied the change; confirmed by reading its state back. */
    data object Confirmed : CommandResult

    /** Rejected before sending: the values don't fit the model. */
    data class Invalid(val validation: Validation) : CommandResult

    /** Rejected before sending: the model can't do this at all. */
    data object Unsupported : CommandResult

    /** Rejected before sending: only possible while [requires] is on. */
    data class Unavailable(val requires: Switch?) : CommandResult

    /** Rejected before sending: the model has no such mode. */
    data class UnknownMode(val index: Int) : CommandResult

    /** Sent, but the brush state didn't change accordingly. */
    data object NotApplied : CommandResult

    /** Communication broke down, typically because the brush fell asleep. */
    data class Failed(val reason: String) : CommandResult
}
