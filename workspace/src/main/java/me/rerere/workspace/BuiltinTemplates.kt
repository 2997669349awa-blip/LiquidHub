package me.rerere.workspace

/**
 * Built-in environment templates. [WorkspaceTemplate.rootfsUrl] is null which
 * means the caller picks the default ABI-compatible rootfs preset.
 */
object BuiltinTemplates {

    val BASE = WorkspaceTemplate(
        id = "builtin-base",
        name = "Base",
        description = "Clean rootfs without extra packages",
        icon = "Terminal",
        builtin = true,
        scripts = emptyList(),
    )

    private val PYTHON_APT =
        "export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends python3 python3-pip python3-venv"
    private val PYTHON_APK = "apk update && apk add python3 py3-pip"
    private val PYTHON_PACMAN = "pacman -Sy --noconfirm python python-pip"

    val PYTHON = WorkspaceTemplate(
        id = "builtin-python",
        name = "Python",
        description = "python3 + pip + venv",
        icon = "Code",
        builtin = true,
        scripts = listOf(
            TemplateScript(
                name = "Install python3, pip and venv",
                command = PYTHON_APT,
                apkCommand = PYTHON_APK,
                aptCommand = PYTHON_APT,
                pacmanCommand = PYTHON_PACMAN,
            ),
        ),
    )

    private val NODE_APT =
        "export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends nodejs npm"
    private val NODE_APK = "apk update && apk add nodejs npm"
    private val NODE_PACMAN = "pacman -Sy --noconfirm nodejs npm"

    val NODE = WorkspaceTemplate(
        id = "builtin-node",
        name = "Node.js",
        description = "nodejs + npm",
        icon = "JavaScript",
        builtin = true,
        scripts = listOf(
            TemplateScript(
                name = "Install nodejs and npm",
                command = NODE_APT,
                apkCommand = NODE_APK,
                aptCommand = NODE_APT,
                pacmanCommand = NODE_PACMAN,
            ),
        ),
    )

    val ALL: List<WorkspaceTemplate> = listOf(BASE, PYTHON, NODE)

    fun byId(id: String): WorkspaceTemplate? = ALL.firstOrNull { it.id == id }
}
