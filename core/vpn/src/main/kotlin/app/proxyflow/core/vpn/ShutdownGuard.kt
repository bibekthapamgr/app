package app.proxyflow.core.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException

/**
 * [SocketGuard] on duplicates of the check's sockets: shutting a duplicate down
 * ends the socket itself, so the engine's blocking connect, read or write returns
 * with an error, while the engine's own descriptor is never closed or reused
 * behind its back. The duplicates are closed once the check has returned.
 */
internal class ShutdownGuard : SocketGuard {
    private val sockets = mutableListOf<ParcelFileDescriptor>()
    private var aborted = false
    private var closed = false

    override fun track(fd: Int) {
        // Called by the engine before it connects the socket, while the descriptor is certainly its own.
        val copy = try {
            ParcelFileDescriptor.fromFd(fd)
        } catch (e: IOException) {
            return
        }
        synchronized(this) {
            if (!aborted && !closed) {
                sockets += copy
                return
            }
            if (aborted) shutdown(copy) // a socket opened after the cancel ends at once
        }
        closeQuietly(copy)
    }

    override fun abort() {
        synchronized(this) {
            if (aborted || closed) return
            aborted = true
            sockets.forEach(::shutdown)
        }
    }

    override fun close() {
        val copies = synchronized(this) {
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        copies.forEach(::closeQuietly)
    }

    private fun shutdown(copy: ParcelFileDescriptor) {
        try {
            // Also ends a connect in progress (the kernel resets a socket in SYN_SENT).
            Os.shutdown(copy.fileDescriptor, OsConstants.SHUT_RDWR)
        } catch (e: ErrnoException) {
            // Not connected (yet), or already closed by the engine: nothing to end.
        }
    }

    private fun closeQuietly(copy: ParcelFileDescriptor) {
        try {
            copy.close()
        } catch (e: IOException) {
            // A duplicate the kernel already let go of.
        }
    }
}
