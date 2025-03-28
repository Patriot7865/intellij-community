// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.*;
import com.intellij.openapi.vfs.impl.GenericZipFile.GenericZipEntry;
import com.intellij.openapi.vfs.limits.FileSizeLimit;
import com.intellij.util.io.ResourceHandle;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ZipHandlerBase extends ArchiveHandler {
  private static final Logger LOG = Logger.getInstance(ZipHandlerBase.class);

  @ApiStatus.Internal
  public static boolean getUseCrcInsteadOfTimestampPropertyValue() {
    return Boolean.getBoolean("zip.handler.uses.crc.instead.of.timestamp");
  }

  @ApiStatus.Internal
  public static @NotNull GenericZipFile getZipFileWrapper(@NotNull Path file) throws IOException {
    GenericZipFile wrapper = isFileLocal(file) ? new JavaZipFileWrapper(file.toFile()) : new JBZipFileWrapper(file.toFile());
    if (LOG.isTraceEnabled()) {
      LOG.trace("Using " + wrapper.getClass().getName() + " to open " + file);
    }
    return wrapper;
  }

  /**
   * Here, a file is considered local if it is accessible by the OS of the IDE.
   * JVM internals assume that all files are local to it.
   */
  private static boolean isFileLocal(Path file) {
    FileSystem fileFs = file.getFileSystem();
    if (fileFs.equals(FileSystems.getDefault())) {
      try {
        // This class is located in the module `platform.core.impl`, which is included in kotlinc.
        // We do not want to have references to MultiRoutingFileSystem in the latter, hence reflection.
        return !(boolean)fileFs.getClass().getMethod("isRoutable", Path.class).invoke(fileFs, file);
      }
      catch (Throwable ignored) { }
    }
    return true;
  }

  public ZipHandlerBase(@NotNull String path) {
    super(path);
  }

  @Override
  protected @NotNull Map<String, EntryInfo> createEntriesMap() throws IOException {
    try (ResourceHandle<GenericZipFile> zipRef = acquireZipHandle()) {
      List<? extends GenericZipEntry> entries = zipRef.get().getEntries();
      Map<String, EntryInfo> result = new ZipEntryMap(entries.size());
      boolean crcAsTimestamp = getUseCrcInsteadOfTimestampPropertyValue();

      for (GenericZipEntry ze : entries) {
        processEntry(result, LOG, ze.getName(), ze.isDirectory() ? null : (parent, name) -> {
          long fileStamp = crcAsTimestamp ? ze.getCrc() : getEntryFileStamp();
          return new EntryInfo(name, false, ze.getSize(), fileStamp, parent);
        });
      }

      return result;
    }
  }

  @Override
  public byte @NotNull [] contentsToByteArray(@NotNull String relativePath) throws IOException {
    try (ResourceHandle<GenericZipFile> zipRef = acquireZipHandle()) {
      GenericZipFile zip = zipRef.get();
      GenericZipEntry entry = zip.getEntry(relativePath);
      if (entry != null) {
        long length = entry.getSize();
        if (FileSizeLimit.isTooLarge(length, FileUtilRt.getExtension(entry.getName()))) {
          throw new FileTooBigException(getPath() + "!/" + relativePath);
        }
        try (InputStream stream = entry.getInputStream()) {
          if (stream != null) {
            // ZipFile.c#Java_java_util_zip_ZipFile_read reads data in 8K (stack allocated) blocks - no sense to create BufferedInputStream
            return StreamUtil.readBytes(stream, (int)length);
          }
        }
      }
    }

    throw new FileNotFoundException(getPath() + "!/" + relativePath);
  }

  @Override
  public @NotNull InputStream getInputStream(@NotNull String relativePath) throws IOException {
    boolean release = true;
    ResourceHandle<GenericZipFile> zipRef = acquireZipHandle();
    try {
      GenericZipFile zip = zipRef.get();
      GenericZipEntry entry = zip.getEntry(relativePath);
      if (entry != null) {
        InputStream stream = entry.getInputStream();
        if (stream != null) {
          long length = entry.getSize();
          if (!FileSizeLimit.isTooLarge(length, FileUtilRt.getExtension(entry.getName()))) {
            try {
              return new BufferExposingByteArrayInputStream(StreamUtil.readBytes(stream, (int)length));
            }
            finally {
              stream.close();
            }
          }
          else {
            release = false;
            return new InputStreamWrapper(stream, zipRef);
          }
        }
      }
    }
    finally {
      if (release) zipRef.close();
    }

    throw new FileNotFoundException(getPath() + "!/" + relativePath);
  }

  @ApiStatus.Internal
  public @NotNull Map<String, Long> getArchiveCrcHashes() throws IOException {
    try (ResourceHandle<GenericZipFile> handle = acquireZipHandle()) {
      GenericZipFile file = handle.get();
      Map<String, Long> result = new Object2LongOpenHashMap<>();
      for (GenericZipEntry entry : file.getEntries()) {
        result.put(normalizeName(entry.getName()), entry.getCrc());
      }
      return result;
    }
  }

  protected abstract long getEntryFileStamp();

  protected abstract @NotNull ResourceHandle<GenericZipFile> acquireZipHandle() throws IOException;

  private static class InputStreamWrapper extends InputStream {
    private final InputStream myStream;
    private final ResourceHandle<GenericZipFile> myZipRef;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    InputStreamWrapper(InputStream stream, ResourceHandle<GenericZipFile> zipRef) {
      myStream = stream;
      myZipRef = zipRef;
    }

    @Override
    public int read() throws IOException {
      return myStream.read();
    }

    @Override
    public int read(byte @NotNull [] b, int off, int len) throws IOException {
      return myStream.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
      return myStream.available();
    }

    @Override
    public void close() throws IOException {
      if (!closed.getAndSet(true)) {
        try {
          myStream.close();
        }
        finally {
          myZipRef.close();
        }
      }
    }
  }
}
