// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.statistic.eventLog.validator.rules.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Class AllowedItemsResourceWeakRefStorage reads data string from file and
 * stores them in WeakReferenсce set.
 */

public class AllowedItemsResourceWeakRefStorage {
  private static final Logger LOG = Logger.getInstance(AllowedItemsResourceWeakRefStorage.class);
  private final Class<?> resourceHolder;
  private final String relativePath;
  private @NotNull WeakReference<Set<String>> itemsRef = new WeakReference<>(null);

  public AllowedItemsResourceWeakRefStorage(@NotNull Class<?> holder, @NotNull String path) {
    resourceHolder = holder;
    relativePath = path;
  }

  protected @Nullable String createValue(@NotNull String value) {
    return value.trim();
  }

  protected @NotNull @Unmodifiable Set<String> readItems() {
    try {
      InputStream resourceStream = resourceHolder.getResourceAsStream(relativePath);
      if (resourceStream == null) {
        throw new IOException("Resource " + relativePath + " not found");
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceStream, StandardCharsets.UTF_8))) {
        final List<String> values = FileUtil.loadLines(reader);
        if (!values.isEmpty()) {
          return ContainerUtil.map2SetNotNull(values, s -> createValue(s));
        }
      }
    }
    catch (IOException e) {
      LOG.info(e);
    }
    return Collections.emptySet();
  }

  public synchronized @NotNull @Unmodifiable Set<String> getItems() {
    Set<String> items = itemsRef.get();
    if (items == null) {
      //noinspection RedundantUnmodifiable
      items = Collections.unmodifiableSet(readItems());
      itemsRef = new WeakReference<>(items);
    }

    return items;
  }
}