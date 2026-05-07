package com.smartTriage.smartTriage_server.module.location.service;

import com.smartTriage.smartTriage_server.module.location.entity.*;
import com.smartTriage.smartTriage_server.module.location.repository.RwLocationRepositories.*;
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
        readCsv("rw-locations/sectors.csv", "sectors").forEach(row -> {
            String districtCode = row[0], sectorCode = row[1], sectorName = row[2];
            if (sectors.findByCode(sectorCode).isPresent()) return;
            Optional<RwDistrict> d = districts.findByCode(districtCode);
            if (d.isEmpty()) {
                log.warn("[rw-locations] sectors.csv row references unknown district code '{}' — skipping",
                        districtCode);
                return;
            }
            sectors.save(RwSector.builder()
                    .district(d.get()).code(sectorCode).name(sectorName).build());
        });
    }

    private void loadCells() {
        // Build a sector-code → entity cache once so the per-row lookup
        // doesn't query the DB per cell.
        Map<String, RwSector> byCode = new HashMap<>();
        sectors.findAll().forEach(s -> byCode.put(s.getCode(), s));

        readCsv("rw-locations/cells.csv", "cells").forEach(row -> {
            String sectorCode = row[0], cellCode = row[1], cellName = row[2];
            if (cells.findByCode(cellCode).isPresent()) return;
            RwSector s = byCode.get(sectorCode);
            if (s == null) {
                log.warn("[rw-locations] cells.csv row references unknown sector code '{}' — skipping",
                        sectorCode);
                return;
            }
            cells.save(RwCell.builder()
                    .sector(s).code(cellCode).name(cellName).build());
        });
    }

    private void loadVillages() {
        Map<String, RwCell> byCode = new HashMap<>();
        cells.findAll().forEach(c -> byCode.put(c.getCode(), c));

        readCsv("rw-locations/villages.csv", "villages").forEach(row -> {
            String cellCode = row[0], villageCode = row[1], villageName = row[2];
            if (villages.findByCode(villageCode).isPresent()) return;
            RwCell c = byCode.get(cellCode);
            if (c == null) {
                log.warn("[rw-locations] villages.csv row references unknown cell code '{}' — skipping",
                        cellCode);
                return;
            }
            villages.save(RwVillage.builder()
                    .cell(c).code(villageCode).name(villageName).build());
        });
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
