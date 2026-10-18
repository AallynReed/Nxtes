package net.mildhope.nxtes.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A signed-in device's tokens for one server account. */
@Serializable
data class Session(
    val server: String,
    val email: String,
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
)

/** What's kept between launches: the session, or the last address used and whether the session ended on its own. */
@Serializable
data class AccountFile(val session: Session? = null, val server: String = DEFAULT_SERVER, val email: String = "", val expired: Boolean = false)

/** The sync account, kept in a file Android's backups leave out, since it holds tokens. */
class SyncAccount(private val file: File) {
    private val _state = MutableStateFlow(read())

    val state: StateFlow<AccountFile> = _state.asStateFlow()
    val session get() = _state.value.session

    fun signIn(session: Session) = write(AccountFile(session, session.server, session.email))

    fun update(session: Session) = write(_state.value.copy(session = session))

    /** Signs out; [expired] says the server ended the session rather than the user. */
    fun signOut(expired: Boolean = false) {
        val now = _state.value
        write(AccountFile(null, now.session?.server ?: now.server, now.session?.email ?: now.email, expired))
    }

    @Synchronized
    private fun write(value: AccountFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(syncJson.encodeToString(AccountFile.serializer(), value))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _state.value = value
    }

    private fun read(): AccountFile = runCatching { syncJson.decodeFromString<AccountFile>(file.readText()) }.getOrNull() ?: AccountFile()
}

const val DEFAULT_SERVER = "https://nxtes.mildhope.net"
