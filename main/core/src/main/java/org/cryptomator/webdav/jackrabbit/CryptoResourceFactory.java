package org.cryptomator.webdav.jackrabbit;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.FilenameUtils;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.DavMethods;
import org.apache.jackrabbit.webdav.DavResource;
import org.apache.jackrabbit.webdav.DavResourceFactory;
import org.apache.jackrabbit.webdav.DavResourceLocator;
import org.apache.jackrabbit.webdav.DavServletRequest;
import org.apache.jackrabbit.webdav.DavServletResponse;
import org.apache.jackrabbit.webdav.DavSession;
import org.apache.jackrabbit.webdav.lock.LockManager;
import org.apache.jackrabbit.webdav.lock.SimpleLockManager;
import org.apache.logging.log4j.util.Strings;
import org.cryptomator.crypto.Cryptor;
import org.eclipse.jetty.http.HttpHeader;

public class CryptoResourceFactory implements DavResourceFactory, FileConstants {

	private final LockManager lockManager = new SimpleLockManager();
	private final Cryptor cryptor;
	private final CryptoWarningHandler cryptoWarningHandler;
	private final Path dataRoot;
	private final FilenameTranslator filenameTranslator;

	CryptoResourceFactory(Cryptor cryptor, CryptoWarningHandler cryptoWarningHandler, String vaultRoot) {
		Path vaultRootPath = FileSystems.getDefault().getPath(vaultRoot);
		this.cryptor = cryptor;
		this.cryptoWarningHandler = cryptoWarningHandler;
		this.dataRoot = vaultRootPath.resolve("d");
		this.filenameTranslator = new FilenameTranslator(cryptor, vaultRootPath);
	}

	@Override
	public final DavResource createResource(DavResourceLocator locator, DavServletRequest request, DavServletResponse response) throws DavException {
		if (locator.isRootLocation()) {
			return createRootDirectory(locator, request.getDavSession());
		}

		final Path filePath = getEncryptedFilePath(locator.getResourcePath());
		final Path dirFilePath = getEncryptedDirectoryFilePath(locator.getResourcePath());
		final String rangeHeader = request.getHeader(HttpHeader.RANGE.asString());
		if (Files.exists(dirFilePath) || DavMethods.METHOD_MKCOL.equals(request.getMethod())) {
			return createDirectory(locator, request.getDavSession(), dirFilePath);
		} else if (Files.exists(filePath) && DavMethods.METHOD_GET.equals(request.getMethod()) && rangeHeader != null) {
			response.setStatus(HttpStatus.SC_PARTIAL_CONTENT);
			return createFilePart(locator, request.getDavSession(), request, filePath);
		} else if (Files.exists(filePath) || DavMethods.METHOD_PUT.equals(request.getMethod())) {
			return createFile(locator, request.getDavSession(), filePath);
		} else {
			// e.g. for MOVE operations:
			return createNonExisting(locator, request.getDavSession(), filePath, dirFilePath);
		}
	}

	@Override
	public final DavResource createResource(DavResourceLocator locator, DavSession session) throws DavException {
		if (locator.isRootLocation()) {
			return createRootDirectory(locator, session);
		}

		final Path filePath = getEncryptedFilePath(locator.getResourcePath());
		final Path dirFilePath = getEncryptedDirectoryFilePath(locator.getResourcePath());
		if (Files.exists(dirFilePath)) {
			return createDirectory(locator, session, dirFilePath);
		} else if (Files.exists(filePath)) {
			return createFile(locator, session, filePath);
		} else {
			// e.g. for MOVE operations:
			return createNonExisting(locator, session, filePath, dirFilePath);
		}
	}

	DavResource createChildDirectoryResource(DavResourceLocator locator, DavSession session, Path existingDirectoryFile) throws DavException {
		return createDirectory(locator, session, existingDirectoryFile);
	}

	DavResource createChildFileResource(DavResourceLocator locator, DavSession session, Path existingFile) throws DavException {
		return createFile(locator, session, existingFile);
	}

	/**
	 * @return Absolute file path for a given cleartext file resourcePath.
	 * @throws IOException
	 */
	private Path getEncryptedFilePath(String relativeCleartextPath) throws DavException {
		final String parentCleartextPath = FilenameUtils.getPathNoEndSeparator(relativeCleartextPath);
		final Path parent = createEncryptedDirectoryPath(parentCleartextPath);
		final String cleartextFilename = FilenameUtils.getName(relativeCleartextPath);
		try {
			final String encryptedFilename = filenameTranslator.getEncryptedFilename(cleartextFilename);
			return parent.resolve(encryptedFilename);
		} catch (IOException e) {
			throw new DavException(DavServletResponse.SC_INTERNAL_SERVER_ERROR, e);
		}
	}

	/**
	 * @return Absolute file path for a given cleartext file resourcePath.
	 * @throws IOException
	 */
	private Path getEncryptedDirectoryFilePath(String relativeCleartextPath) throws DavException {
		final String parentCleartextPath = FilenameUtils.getPathNoEndSeparator(relativeCleartextPath);
		final Path parent = createEncryptedDirectoryPath(parentCleartextPath);
		final String cleartextFilename = FilenameUtils.getName(relativeCleartextPath);
		try {
			final String encryptedFilename = filenameTranslator.getEncryptedDirFileName(cleartextFilename);
			return parent.resolve(encryptedFilename);
		} catch (IOException e) {
			throw new DavException(DavServletResponse.SC_INTERNAL_SERVER_ERROR, e);
		}
	}

	/**
	 * @return Absolute directory path for a given cleartext directory resourcePath.
	 * @throws IOException
	 */
	private Path createEncryptedDirectoryPath(String relativeCleartextPath) throws DavException {
		assert Strings.isEmpty(relativeCleartextPath) || !relativeCleartextPath.endsWith("/");
		try {
			final Path result;
			if (Strings.isEmpty(relativeCleartextPath)) {
				// root level
				final String fixedRootDirectory = cryptor.encryptDirectoryPath("", FileSystems.getDefault().getSeparator());
				result = dataRoot.resolve(fixedRootDirectory);
			} else {
				final String parentCleartextPath = FilenameUtils.getPathNoEndSeparator(relativeCleartextPath);
				final Path parent = createEncryptedDirectoryPath(parentCleartextPath);
				final String cleartextFilename = FilenameUtils.getName(relativeCleartextPath);
				final String encryptedFilename = filenameTranslator.getEncryptedDirFileName(cleartextFilename);
				final Path directoryFile = parent.resolve(encryptedFilename);
				final String directoryId = filenameTranslator.getDirectoryId(directoryFile, true);
				final String directory = cryptor.encryptDirectoryPath(directoryId, FileSystems.getDefault().getSeparator());
				result = dataRoot.resolve(directory);
			}
			Files.createDirectories(result);
			return result;
		} catch (IOException e) {
			throw new DavException(DavServletResponse.SC_INTERNAL_SERVER_ERROR, e);
		}
	}

	private EncryptedFile createFilePart(DavResourceLocator locator, DavSession session, DavServletRequest request, Path filePath) {
		return new EncryptedFilePart(this, locator, session, request, lockManager, cryptor, cryptoWarningHandler, filePath);
	}

	private EncryptedFile createFile(DavResourceLocator locator, DavSession session, Path filePath) {
		return new EncryptedFile(this, locator, session, lockManager, cryptor, cryptoWarningHandler, filePath);
	}

	private EncryptedDir createRootDirectory(DavResourceLocator locator, DavSession session) throws DavException {
		final Path rootFile = dataRoot.resolve(ROOT_FILE);
		final Path rootDir = filenameTranslator.getEncryptedDirectoryPath("");
		try {
			// make sure, root dir always exists.
			// create dir first (because it fails silently, if alreay existing)
			Files.createDirectories(rootDir);
			Files.createFile(rootFile);
		} catch (FileAlreadyExistsException e) {
			// no-op
		} catch (IOException e) {
			throw new DavException(DavServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		return createDirectory(locator, session, dataRoot.resolve(ROOT_FILE));
	}

	private EncryptedDir createDirectory(DavResourceLocator locator, DavSession session, Path filePath) {
		return new EncryptedDir(this, locator, session, lockManager, cryptor, filenameTranslator, filePath);
	}

	private NonExistingNode createNonExisting(DavResourceLocator locator, DavSession session, Path filePath, Path dirFilePath) {
		return new NonExistingNode(this, locator, session, lockManager, cryptor, filePath, dirFilePath);
	}

}
