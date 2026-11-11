package app.amphora.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.AppGraph
import app.amphora.Message
import app.amphora.sdk.library.AddResult
import app.amphora.sdk.library.PeArch
import app.amphora.system.StorageAccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the browser shows for the current directory. */
sealed interface Browse {
    data object Loading : Browse

    /** Outside the sideload directory, nothing useful is visible without all-files access. */
    data class NeedsAccess(val dir: File) : Browse

    data class Unreadable(val dir: File) : Browse

    data class Ready(
        val listing: DirListing,
        /** Filled in after the listing shows: reading headers is cheap but not free. */
        val archs: Map<File, PeArch?> = emptyMap(),
    ) : Browse
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddGameViewModel(private val graph: AppGraph) : ViewModel() {
    private val _access = MutableStateFlow(StorageAccess.granted())
    val access: StateFlow<Boolean> = _access.asStateFlow()

    private val _roots = MutableStateFlow(StorageRoots.list(graph.app, graph.sdk.sideloadDir))
    val roots: StateFlow<List<StorageRoot>> = _roots.asStateFlow()

    private val _dir = MutableStateFlow(startDir())
    val dir: StateFlow<File> = _dir.asStateFlow()

    private val reloads = MutableStateFlow(0)

    val browse: StateFlow<Browse> =
        combine(_dir, _access, reloads) { dir, access, _ -> dir to access }
            .flatMapLatest { (dir, access) -> browse(dir, access) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Browse.Loading)

    /** Executables already in the library, by path. */
    val inLibrary: StateFlow<Map<String, String>> =
        graph.sdk.library.games
            .map { games -> games.orEmpty().associate { it.exePath to it.id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _adding = MutableStateFlow<File?>(null)

    /** The executable being hashed and added, if any. */
    val adding: StateFlow<File?> = _adding.asStateFlow()

    private val _added = Channel<String>(Channel.BUFFERED)

    /** Ids of games just added (or found already added); the screen navigates to them. */
    val added: Flow<String> = _added.receiveAsFlow()

    fun open(dir: File) {
        _dir.value = dir
        graph.prefs.browseDir = dir.path
    }

    /** Goes to the parent directory; false at the top of a root. */
    fun up(): Boolean {
        val dir = _dir.value
        val root = StorageRoot.of(_roots.value, dir)
        val parent = dir.parentFile
        if (root == null || dir.path == root.dir.path || parent == null) return false
        open(parent)
        return true
    }

    /** On resume: the user may have granted access or mounted a card meanwhile. */
    fun refresh() {
        _access.value = StorageAccess.granted()
        _roots.value = StorageRoots.list(graph.app, graph.sdk.sideloadDir)
        reloads.value++
    }

    fun add(exe: File) {
        if (_adding.value != null) return
        _adding.value = exe
        viewModelScope.launch {
            try {
                when (val result = graph.sdk.library.add(exe)) {
                    is AddResult.Added -> {
                        graph.messages.show("已添加「${result.game.title}」", Message.Tone.Ok)
                        _added.send(result.game.id)
                    }
                    is AddResult.AlreadyAdded -> {
                        graph.messages.show("「${result.game.title}」已经在游戏库里", Message.Tone.Info)
                        _added.send(result.game.id)
                    }
                    is AddResult.Rejected -> graph.messages.show("没能添加：${result.reason}", Message.Tone.Warn)
                }
            } finally {
                _adding.value = null
            }
        }
    }

    private fun browse(dir: File, access: Boolean): Flow<Browse> = flow {
        val root = StorageRoot.of(_roots.value, dir)
        if (!access && root?.kind != StorageRoot.Kind.Sideload) {
            emit(Browse.NeedsAccess(dir))
            return@flow
        }
        val listing = withContext(Dispatchers.IO) { DirListing.read(dir) }
        if (listing == null) {
            emit(Browse.Unreadable(dir))
            return@flow
        }
        emit(Browse.Ready(listing))
        if (listing.exes.isNotEmpty()) {
            emit(Browse.Ready(listing, listing.exes.associate { it.file to graph.sdk.library.inspect(it.file) }))
        }
    }

    /** Where the browser was last, if still there; else the first root that can be read. */
    private fun startDir(): File {
        val roots = _roots.value
        val remembered = graph.prefs.browseDir?.let(::File)
        if (remembered != null && remembered.isDirectory && StorageRoot.of(roots, remembered) != null) return remembered
        val preferred =
            if (_access.value) {
                roots.firstOrNull { it.kind == StorageRoot.Kind.Internal }
            } else {
                roots.firstOrNull { it.kind == StorageRoot.Kind.Sideload }
            }
        return (preferred ?: roots.firstOrNull())?.dir ?: File("/storage/emulated/0")
    }
}
