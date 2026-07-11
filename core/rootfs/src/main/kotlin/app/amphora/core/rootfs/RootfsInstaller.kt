package app.amphora.core.rootfs

import app.amphora.core.rootfs.model.RootfsSpec

/**
 * imagefs install / extract / version (RFC §6 / §11). Backed by the
 * winlator-imagefs build (Bionic 42-pack) plus termuxfs runtime libs whose
 * rpath (`/data/data/com.termux/files/usr/lib`) must be reproduced (RFC §11).
 */
interface RootfsInstaller {
    suspend fun ensureInstalled(spec: RootfsSpec): Boolean
    suspend fun currentVersion(): String?
}
