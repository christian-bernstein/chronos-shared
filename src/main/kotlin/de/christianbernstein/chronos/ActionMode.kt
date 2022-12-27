package de.christianbernstein.chronos

enum class ActionMode {

    ACTION, REACTION;

    companion object {
        fun ifReaction(mode: ActionMode, action: Runnable) = if (mode == REACTION) action.run() else {}
    }
}
