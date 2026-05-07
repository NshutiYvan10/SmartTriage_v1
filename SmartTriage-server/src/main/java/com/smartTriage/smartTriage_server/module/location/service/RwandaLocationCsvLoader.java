package com.smartTriage.smartTriage_server.module.location.service;

import com.smartTriage.smartTriage_server.module.location.entity.*;
import com.smartTriage.smartTriage_server.module.location.repository.RwDistrictRepository;
import com.smartTriage.smartTriage_server.module.location.repository.RwSectorRepository;
import com.smartTriage.smartTriage_server.module.location.repository.RwCellRepository;
import com.smartTriage.smartTriage_server.module.location.repository.RwVillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bootstrap-time loader for Rwanda sector / cell / village reference
 * data. The 5 provinces and 30 districts are seeded by Flyway V47;
 * the lower three levels (~416 sectors, ~2148 cells, ~14837 villages)
 * are too voluminous to embed in a SQL migration and the official
 * dataset evolves outside our migration cadence — so they're loaded
 * from CSV files placed under {@code classpath:rw-locations/}.
 *
 * <h2>Expected files</h2>
 * <pre>
 *   src/main/resources/rw-locations/sectors.csv
 *     Header:  district_code,sector_code,sector_name
 *     Example: RW.01.01,RW.01.01.01,Bumbogo
 *
 *   src/main/resources/rw-locations/cells.csv
 *     Header:  sector_code,cell_code,cell_name
 *
 *   src/main/resources/rw-locations/villages.csv
 *     Header:  cell_code,village_code,village_name
 * </pre>
 *
 * <p>If a file is missing, the loader logs a warning and continues —
 * the patient/hospital forms will fall back to "I only know my
 * district" granularity until the file is provided. No file ever
 * causes a startup failure; this is reference data, not blocking
 * configuration.
 *
 * <h2>Idempotency</h2>
 * Each row inserts only when its {@code code} is not already present.
 * Re-running on a populated database produces zero writes; safe to
 * leave the loader on permanently.
 *
 * <h2>Source of truth</h2>
 * Use the National Institute of Statistics of Rwanda (NISR) /
 * Rwanda Governance Board administrative-units dataset. The codes
 * supplied in your CSV become the join keys; once loaded, they should
 * not change without coordinated migration of any patient/hospital
 * rows that reference them.
 *
 * <p>Triggered after Spring is fully up so JPA + Flyway have already
 * run and the seeded provinces/districts are available for FK lookup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RwandaLocationCsvLoader {

    private final RwDistrictRepository districts;
    private final RwSectorRepository sectors;
    private final RwCellRepository cells;
    private final RwVillageRepository villages;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadOnStartup() {
        loadSectors();
        loadCells();
        loadVillages();
    }

    private void loadSectors() {
        // Pre-cache existing codes (idempotency gate) and the parent
        // entities (FK resolution) so the per-row work is O(1). With
        // 416 sectors this is overkill; the same pattern at village
        // level (14k+ rows) is what makes startup fast.
        java.util.Set<String> existing = new java.util.HashSet<>();
        sectors.findAll().forEach(s -> existing.add(s.getCode()));
        Map<String, RwDistrict> districtsByCode = new HashMap<>();
        districts.findAll().forEach(d -> districtsByCode.put(d.getCode(), d));

        int inserted = 0, skippedExisting = 0, skippedOrphan = 0;
        for (String[] row : readCsv("rw-locations/sectors.csv", "sectors")) {
            String districtCode = row[0], sectorCode = row[1], sectorName = row[2];
            if (existing.contains(sectorCode)) { skippedExisting++; continue; }
            RwDistrict d = districtsByCode.get(districtCode);
            if (d == null) {
                log.warn("[rw-locations] sectors.csv row references unknown district code '{}' — skipping",
                        districtCode);
                skippedOrphan++;
                continue;
            }
            sectors.save(RwSector.builder()
                    .district(d).code(sectorCode).name(sectorName).build());
            inserted++;
        }
        if (inserted + skippedOrphan > 0) {
            log.info("[rw-locations] sectors: inserted={} existing={} orphan={}",
                    inserted, skippedExisting, skippedOrphan);
        }
    }

    private void loadCells() {
        java.util.Set<String> existing = new java.util.HashSet<>();
        cells.findAll().forEach(c -> existing.add(c.getCode()));
        Map<String, RwSector> sectorsByCode = new HashMap<>();
        sectors.findAll().forEach(s -> sectorsByCode.put(s.getCode(), s));

        int inserted = 0, skippedExisting = 0, skippedOrphan = 0;
        for (String[] row : readCsv("rw-locations/cells.csv", "cells")) {
            String sectorCode = row[0], cellCode = row[1], cellName = row[2];
            if (existing.contains(cellCode)) { skippedExisting++; continue; }
            RwSector s = sectorsByCode.get(sectorCode);
            if (s == null) {
                log.warn("[rw-locations] cells.csv row references unknown sector code '{}' — skipping",
                        sectorCode);
                skippedOrphan++;
                continue;
            }
            cells.save(RwCell.builder()
                    .sector(s).code(cellCode).name(cellName).build());
            inserted++;
        }
        if (inserted + skippedOrphan > 0) {
            log.info("[rw-locations] cells: inserted={} existing={} orphan={}",
                    inserted, skippedExisting, skippedOrphan);
        }
    }

    private void loadVillages() {
        // Pre-cache existing village codes and parent cells. With ~14.8k
        // villages this is the hot path: skipping individual findByCode
        // round-trips drops idempotent restart from O(N) DB queries to
        // a single bulk SELECT.
        java.util.Set<String> existing = new java.util.HashSet<>();
        villages.findAll().forEach(v -> existing.add(v.getCode()));
        Map<String, RwCell> cellsByCode = new HashMap<>();
        cells.findAll().forEach(c -> cellsByCode.put(c.getCode(), c));

        int inserted = 0, skippedExisting = 0, skippedOrphan = 0;
        long t0 = System.currentTimeMillis();
        for (String[] row : readCsv("rw-locations/villages.csv", "villages")) {
            String cellCode = row[0], villageCode = row[1], villageName = row[2];
            if (existing.contains(villageCode)) { skippedExisting++; continue; }
            RwCell c = cellsByCode.get(cellCode);
            if (c == null) {
                log.warn("[rw-locations] villages.csv row references unknown cell code '{}' — skipping",
                        cellCode);
                skippedOrphan++;
                continue;
            }
            villages.save(RwVillage.builder()
                    .cell(c).code(villageCode).name(villageName).build());
            inserted++;
            // First-time bootstrap will insert ~15k rows; emit progress
            // every 2000 so an admin watching the log knows it's making
            // headway, not stalled.
            if (inserted % 2000 == 0) {
                log.info("[rw-locations] villages: inserted {} so far ({}ms elapsed)…",
                        inserted, System.currentTimeMillis() - t0);
            }
        }
        if (inserted + skippedOrphan > 0) {
            log.info("[rw-locations] villages: inserted={} existing={} orphan={} ({}ms)",
                    inserted, skippedExisting, skippedOrphan,
                    System.currentTimeMillis() - t0);
        }
    }

    /**
     * Read a 3-column CSV by classpath, honouring a single header row.
     * Quotes / commas inside fields are not handled — Rwandan place
     * names don't include either, and the input is reference data
     * curated offline. Yields {@code String[]{c1, c2, c3}} per row.
     * Missing file logs a warning and returns an empty stream.
     */
    private Iterable<String[]> readCsv(String path, String label) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("[rw-locations] {} CSV not found at classpath:{} — skipping. "
                    + "Patient registration will be limited to province + district until "
                    + "the official NISR dataset is provided.", label, path);
            return java.util.List.of();
        }
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        int loaded = 0, skipped = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean firstRow = true;
            while ((line = reader.readLine()) != null) {
                if (firstRow) { firstRow = false; continue; } // header
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 3) {
                    skipped++;
                    continue;
                }
                rows.add(new String[]{
                        cols[0].trim(), cols[1].trim(), cols[2].trim()
                });
                loaded++;
            }
            log.info("[rw-locations] {}: parsed {} rows from {} ({} malformed skipped)",
                    label, loaded, path, skipped);
        } catch (Exception e) {
            log.error("[rw-locations] Failed to read {}: {}", path, e.getMessage(), e);
        }
        return rows;
    }
}
