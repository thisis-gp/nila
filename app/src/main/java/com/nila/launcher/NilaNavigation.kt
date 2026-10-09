package com.nila.launcher

enum class NilaScreen {
    Welcome,
    Permissions,
    Home,
    Voice,
    Answer,
    GeneratedCanvas,
    Food,
    PreparedOrder,
    ActionResult,
    People,
    Memory,
    Capabilities,
    Apps,
    Activity,
    Settings,
}

class NilaNavigation(start: NilaScreen) {
    private val history = ArrayDeque<NilaScreen>().apply { add(start) }

    val current: NilaScreen get() = history.last()
    val canGoBack: Boolean get() = history.size > 1

    fun open(screen: NilaScreen): NilaScreen {
        if (screen != current) history.add(screen)
        return current
    }

    fun back(): NilaScreen {
        if (canGoBack) history.removeLast()
        return current
    }
}
