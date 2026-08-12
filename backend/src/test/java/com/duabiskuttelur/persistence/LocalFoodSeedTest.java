package com.duabiskuttelur.persistence;

import com.duabiskuttelur.service.NutrientPlausibility;
import com.duabiskuttelur.service.NutritionCacheService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real migrations against a real H2 and checks whatever
 * {@code R__local_food_seed.sql} put in the table.
 *
 * <p>These rows carry the app's highest trust badge, are transcribed by hand
 * from published composition tables, and are the one part of the nutrition
 * pipeline with no automated source to compare against. The failure mode is a
 * slipped decimal that still looks like a plausible dish, so the checks are
 * arithmetic — see {@link NutrientPlausibility}.
 *
 * <p><b>The seed file currently ships empty, so the per-row assertions here pass
 * vacuously.</b> That is stated rather than hidden: they exist to fail the build
 * the moment badly-transcribed data is added, which is exactly when nobody is
 * looking. The checks themselves are proven non-vacuously by
 * {@code NutrientPlausibilityTest}, and the schema assertions below run either
 * way.
 */
class LocalFoodSeedTest {

    private record SeededFood(String canonicalName, String displayName, String source, String provenance,
                              double typicalGrams, NutrientPlausibility.Row nutrients) {
    }

    private static Connection migrated(String dbName) throws Exception {
        String url = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return DriverManager.getConnection(url, "sa", "");
    }

    private static List<SeededFood> seeded(Connection connection) throws Exception {
        List<SeededFood> foods = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select canonical_name, display_name, source, provenance, typical_grams,
                            calories_per100g, protein_per100g, carbs_per100g, fat_per100g,
                            fiber_per100g, sugar_per100g, sodium_per100g
                       from local_food
                     """)) {
            while (rs.next()) {
                foods.add(new SeededFood(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5),
                        new NutrientPlausibility.Row(rs.getString(1), rs.getDouble(6), rs.getDouble(7),
                                rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getDouble(11),
                                rs.getDouble(12))));
            }
        }
        return foods;
    }

    @Test
    void everySeededRowIsArithmeticallyPlausible() throws Exception {
        try (Connection connection = migrated("local-food-plausible")) {
            for (SeededFood food : seeded(connection)) {
                assertEquals(List.of(), NutrientPlausibility.problems(food.nutrients()),
                        "local_food row '" + food.canonicalName() + "' does not add up");
            }
        }
    }

    /**
     * A name that is not canonicalized can never be hit: the lookup canonicalizes
     * the model's dish name before comparing, so "Nasi Lemak" in this column is a
     * row that silently never matches anything.
     */
    @Test
    void everySeededNameAndAliasIsAlreadyCanonicalized() throws Exception {
        try (Connection connection = migrated("local-food-canonical")) {
            for (SeededFood food : seeded(connection)) {
                assertEquals(NutritionCacheService.canonicalize(food.canonicalName()), food.canonicalName(),
                        "local_food.canonical_name is not in canonical form");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select alias from local_food_alias")) {
                while (rs.next()) {
                    String alias = rs.getString(1);
                    assertEquals(NutritionCacheService.canonicalize(alias), alias,
                            "local_food_alias.alias is not in canonical form");
                }
            }
        }
    }

    /** A composition figure with no citation cannot be checked by the next person. */
    @Test
    void everySeededRowSaysWhereItCameFrom() throws Exception {
        try (Connection connection = migrated("local-food-provenance")) {
            for (SeededFood food : seeded(connection)) {
                assertTrue(food.source() != null && !food.source().isBlank(),
                        "local_food row '" + food.canonicalName() + "' has no source");
                assertTrue(food.provenance() != null && !food.provenance().isBlank(),
                        "local_food row '" + food.canonicalName() + "' has no provenance citation");
                assertTrue(food.typicalGrams() > 0,
                        "local_food row '" + food.canonicalName() + "' has no typical serving");
                assertTrue(food.displayName() != null && !food.displayName().isBlank());
            }
        }
    }

    /**
     * Not vacuous: the schema has to exist and be shaped right whether or not
     * anything has been curated into it yet.
     */
    @Test
    void theTableAndItsAliasIndexExist() throws Exception {
        try (Connection connection = migrated("local-food-schema");
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select count(*) from local_food")) {
                assertTrue(rs.next());
            }
            // The alias lookup is an equality match run up to sixty times per menu
            // scan; without an index it is a full scan of the table each time.
            try (ResultSet rs = statement.executeQuery("""
                    select count(*) from information_schema.indexes
                     where table_name = 'LOCAL_FOOD_ALIAS'
                    """)) {
                assertTrue(rs.next() && rs.getInt(1) > 0, "no index on local_food_alias");
            }
        }
    }
}
