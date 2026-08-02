package app.amphora.core.container.model

@JvmInline
value class ContainerId(val value: String)

data class Container(val id: ContainerId, val rootPath: String, val winePrefixPath: String)

/**
 * MVP single shared container (RFC §9: multi-prefix is v0.2).
 *
 * One definition because the two sides have to agree: the app passes this id to
 * [app.amphora.core.engine.model.LaunchSpec], and the engine falls back to it when
 * an id does not parse. They used to be declared separately, as `"1"` in the app
 * and `1` in the engine.
 */
val DEFAULT_CONTAINER_ID = ContainerId("1")
