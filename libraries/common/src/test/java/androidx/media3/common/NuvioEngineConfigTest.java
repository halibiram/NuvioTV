/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link NuvioEngineConfig}. */
@RunWith(AndroidJUnit4.class)
public class NuvioEngineConfigTest {

  @After
  public void tearDown() {
    // Reset to default after each test to avoid state leakage.
    NuvioEngineConfig.set(NuvioEngineConfig.stockMode());
  }

  // ─── Default (stock) mode ───

  @Test
  public void defaultConfig_isStockMode() {
    NuvioEngineConfig config = NuvioEngineConfig.get();

    assertThat(config.getAllocationMode())
        .isEqualTo(NuvioEngineConfig.AllocationMode.STOCK_HEAP);
    assertThat(config.getDataPipelineMode())
        .isEqualTo(NuvioEngineConfig.DataPipelineMode.STOCK);
    assertThat(config.getExtractorScratchSize())
        .isEqualTo(NuvioEngineConfig.STOCK_EXTRACTOR_SCRATCH_SIZE);
  }

  @Test
  public void defaultConfig_nativeAllocationDisabled() {
    assertThat(NuvioEngineConfig.get().isNativeAllocationEnabled()).isFalse();
  }

  @Test
  public void defaultConfig_zeroCopyDisabled() {
    assertThat(NuvioEngineConfig.get().isZeroCopyEnabled()).isFalse();
  }

  // ─── stockMode() preset ───

  @Test
  public void stockMode_hasExpectedValues() {
    NuvioEngineConfig stock = NuvioEngineConfig.stockMode();

    assertThat(stock.getAllocationMode())
        .isEqualTo(NuvioEngineConfig.AllocationMode.STOCK_HEAP);
    assertThat(stock.getDataPipelineMode())
        .isEqualTo(NuvioEngineConfig.DataPipelineMode.STOCK);
    assertThat(stock.getExtractorScratchSize()).isEqualTo(4096);
    assertThat(stock.isNativeAllocationEnabled()).isFalse();
    assertThat(stock.isZeroCopyEnabled()).isFalse();
  }

  // ─── nuvioMode() preset ───

  @Test
  public void nuvioMode_hasExpectedValues() {
    NuvioEngineConfig nuvio = NuvioEngineConfig.nuvioMode();

    assertThat(nuvio.getAllocationMode())
        .isEqualTo(NuvioEngineConfig.AllocationMode.NATIVE_OFF_HEAP);
    assertThat(nuvio.getDataPipelineMode())
        .isEqualTo(NuvioEngineConfig.DataPipelineMode.ZERO_COPY);
    assertThat(nuvio.getExtractorScratchSize()).isEqualTo(65536);
    assertThat(nuvio.isNativeAllocationEnabled()).isTrue();
    assertThat(nuvio.isZeroCopyEnabled()).isTrue();
  }

  // ─── Global set/get ───

  @Test
  public void setGlobal_thenGet_returnsSameConfig() {
    NuvioEngineConfig nuvio = NuvioEngineConfig.nuvioMode();
    NuvioEngineConfig.set(nuvio);

    assertThat(NuvioEngineConfig.get()).isSameInstanceAs(nuvio);
  }

  @Test
  public void setGlobal_switchesMode() {
    // Start with stock (default)
    assertThat(NuvioEngineConfig.get().isZeroCopyEnabled()).isFalse();

    // Switch to nuvio
    NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode());
    assertThat(NuvioEngineConfig.get().isZeroCopyEnabled()).isTrue();
    assertThat(NuvioEngineConfig.get().isNativeAllocationEnabled()).isTrue();

    // Switch back to stock
    NuvioEngineConfig.set(NuvioEngineConfig.stockMode());
    assertThat(NuvioEngineConfig.get().isZeroCopyEnabled()).isFalse();
    assertThat(NuvioEngineConfig.get().isNativeAllocationEnabled()).isFalse();
  }

  // ─── Builder ───

  @Test
  public void builder_defaultValues_matchStockMode() {
    NuvioEngineConfig config = new NuvioEngineConfig.Builder().build();

    assertThat(config.getAllocationMode())
        .isEqualTo(NuvioEngineConfig.AllocationMode.STOCK_HEAP);
    assertThat(config.getDataPipelineMode())
        .isEqualTo(NuvioEngineConfig.DataPipelineMode.STOCK);
    assertThat(config.getExtractorScratchSize()).isEqualTo(4096);
  }

  @Test
  public void builder_customAllocationOnly_leavesOthersDefault() {
    NuvioEngineConfig config =
        new NuvioEngineConfig.Builder()
            .setAllocationMode(NuvioEngineConfig.AllocationMode.NATIVE_OFF_HEAP)
            .build();

    assertThat(config.isNativeAllocationEnabled()).isTrue();
    // Pipeline mode stays stock
    assertThat(config.isZeroCopyEnabled()).isFalse();
    // Scratch stays at stock default
    assertThat(config.getExtractorScratchSize()).isEqualTo(4096);
  }

  @Test
  public void builder_customPipelineOnly_leavesOthersDefault() {
    NuvioEngineConfig config =
        new NuvioEngineConfig.Builder()
            .setDataPipelineMode(NuvioEngineConfig.DataPipelineMode.ZERO_COPY)
            .build();

    assertThat(config.isZeroCopyEnabled()).isTrue();
    // Allocation stays stock
    assertThat(config.isNativeAllocationEnabled()).isFalse();
    assertThat(config.getExtractorScratchSize()).isEqualTo(4096);
  }

  @Test
  public void builder_customScratchSize() {
    int customSize = 32768;
    NuvioEngineConfig config =
        new NuvioEngineConfig.Builder()
            .setExtractorScratchSize(customSize)
            .build();

    assertThat(config.getExtractorScratchSize()).isEqualTo(customSize);
  }

  @Test
  public void builder_fullCustomConfig() {
    NuvioEngineConfig config =
        new NuvioEngineConfig.Builder()
            .setAllocationMode(NuvioEngineConfig.AllocationMode.NATIVE_OFF_HEAP)
            .setDataPipelineMode(NuvioEngineConfig.DataPipelineMode.ZERO_COPY)
            .setExtractorScratchSize(131072)
            .build();

    assertThat(config.isNativeAllocationEnabled()).isTrue();
    assertThat(config.isZeroCopyEnabled()).isTrue();
    assertThat(config.getExtractorScratchSize()).isEqualTo(131072);
  }

  // ─── Constants ───

  @Test
  public void stockScratchSize_is4K() {
    assertThat(NuvioEngineConfig.STOCK_EXTRACTOR_SCRATCH_SIZE).isEqualTo(4096);
  }

  @Test
  public void nuvioScratchSize_is64K() {
    assertThat(NuvioEngineConfig.NUVIO_EXTRACTOR_SCRATCH_SIZE).isEqualTo(65536);
  }
}
