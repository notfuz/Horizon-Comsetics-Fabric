package fuz.horizoncosmetics.client.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class CosmeticAssetCache implements AutoCloseable {
	private static final int MAX_ITEM_BYTES = 1_048_576;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Horizon cosmetics cache");
		thread.setDaemon(true);
		return thread;
	});

	private final Path directory;
	private final ConcurrentMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();

	public CosmeticAssetCache(Path directory) {
		this.directory = directory;
	}

	public CompletableFuture<byte[]> get(String itemId, String hash, Supplier<CompletableFuture<byte[]>> downloader) {
		if (!itemId.matches("[A-Za-z0-9_-]{1,128}") || !hash.matches("[A-Za-z0-9_-]{1,256}")) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid item identifier or hash"));
		}
		String key = itemId + "@" + hash;
		return inFlight.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> read(key), IO)
				.thenCompose(cached -> cached != null
						? CompletableFuture.completedFuture(cached)
						: downloader.get().thenApply(bytes -> persist(key, bytes)))
				.whenComplete((bytes, error) -> inFlight.remove(key)));
	}

	private byte[] read(String key) {
		Path file = fileFor(key);
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			long size = Files.size(file);
			if (size <= 0 || size > MAX_ITEM_BYTES) {
				Files.deleteIfExists(file);
				return null;
			}
			return Files.readAllBytes(file);
		} catch (IOException exception) {
			throw new CacheException("Could not read the cosmetic asset cache", exception);
		}
	}

	private byte[] persist(String key, byte[] bytes) {
		if (bytes.length == 0 || bytes.length > MAX_ITEM_BYTES) {
			throw new CacheException("Cosmetic item exceeded the 1 MiB asset limit");
		}
		Path file = fileFor(key);
		Path temp = file.resolveSibling(file.getFileName() + ".part");
		try {
			Files.createDirectories(directory);
			Files.write(temp, bytes);
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException atomicMoveUnsupported) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return bytes;
		} catch (IOException exception) {
			throw new CacheException("Could not persist a cosmetic asset", exception);
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException exception) {
				throw new CacheException("Could not clean up a temporary cosmetic asset", exception);
			}
		}
	}

	private Path fileFor(String key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return directory.resolve(HexFormat.of().formatHex(digest) + ".json");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	@Override
	public void close() {
		IO.shutdown();
	}

	public static final class CacheException extends RuntimeException {
		public CacheException(String message) {
			super(message);
		}

		public CacheException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
