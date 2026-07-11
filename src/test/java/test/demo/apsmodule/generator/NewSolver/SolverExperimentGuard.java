package test.demo.apsmodule.generator.NewSolver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process lease used by long-running solver experiment harnesses. */
final class SolverExperimentGuard implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private SolverExperimentGuard(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static SolverExperimentGuard acquire(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        FileChannel channel = FileChannel.open(absolute,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw busy(absolute, null);
            }
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(("pid=" + ProcessHandle.current().pid()
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            return new SolverExperimentGuard(channel, lock);
        } catch (OverlappingFileLockException ex) {
            throw busy(absolute, ex);
        } catch (IOException | RuntimeException ex) {
            channel.close();
            throw ex;
        }
    }

    private static IOException busy(Path path, Exception cause) {
        return new IOException("Another solver experiment holds the lease: " + path, cause);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
