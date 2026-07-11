package app.amphora.core.container.model

@JvmInline
value class ContainerId(val value: String)

data class Container(
    val id: ContainerId,
    val rootPath: String,
    val winePrefixPath: String,
)
