rootProject.name = "personal-assistant"
include("core", "channels", "providers", "tools", "memory", "app")
include(
    "examples:sysinfo-tool-plugin",
    "examples:calculator-tool-plugin",
    "examples:weather-tool-plugin"
)
