package db.migration;

import com.duabiskuttelur.service.FoodKeywords;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Repairs the {@code beverage_only}/{@code coffee_only} columns that the old
 * substring matcher got wrong (see FoodKeywords).
 *
 * <p>These are decided when a meal is saved, not when it is read, so correcting
 * the matcher does nothing for meals already logged — a steak recorded as a
 * drinks-only meal stays one, and keeps its "Liquid Dinner" badge, forever.
 *
 * <p>Java rather than SQL because the verdict is per food item and those names
 * live inside {@code result_json}; no amount of SQL over the joined
 * {@code summary} column reproduces "every item matched". Reusing
 * {@link FoodKeywords} rather than restating the keyword lists here is the
 * point — a copy would drift from the rule it is supposed to be repairing to.
 *
 * <p>Only rows currently flagged {@code true} are examined. Whole-word matching
 * is strictly narrower than the substring matching it replaces, so every
 * correction can only run true → false; a row already false cannot have been a
 * false positive.
 */
public class V8__Repair_beverage_and_coffee_flags extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V8__Repair_beverage_and_coffee_flags.class);

    private static final String SELECT_FLAGGED = """
            SELECT id, result_json, beverage_only, coffee_only
              FROM meal_analysis
             WHERE beverage_only = TRUE OR coffee_only = TRUE
            """;

    private static final String UPDATE_ROW =
            "UPDATE meal_analysis SET beverage_only = ?, coffee_only = ? WHERE id = ?";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        ObjectMapper mapper = new ObjectMapper();
        int examined = 0;
        int repaired = 0;

        try (PreparedStatement select = connection.prepareStatement(SELECT_FLAGGED);
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(UPDATE_ROW)) {

            while (rows.next()) {
                examined++;
                long id = rows.getLong("id");
                boolean storedBeverage = rows.getBoolean("beverage_only");
                boolean storedCoffee = rows.getBoolean("coffee_only");

                List<String> names = foodNames(mapper, rows.getString("result_json"));
                if (names.isEmpty()) {
                    // Nothing to re-derive from. Leaving the row as-is is the
                    // conservative choice: it is at worst still wrong, never
                    // newly wrong in the other direction.
                    continue;
                }

                boolean beverageOnly = FoodKeywords.allMatch(names, FoodKeywords.BEVERAGE);
                boolean coffeeOnly = FoodKeywords.allMatch(names, FoodKeywords.COFFEE);
                if (beverageOnly == storedBeverage && coffeeOnly == storedCoffee) {
                    continue;
                }

                update.setBoolean(1, beverageOnly);
                update.setBoolean(2, coffeeOnly);
                update.setLong(3, id);
                update.addBatch();
                repaired++;
            }
            if (repaired > 0) {
                update.executeBatch();
            }
        }
        log.info("Beverage/coffee flag repair: {} flagged row(s) examined, {} corrected", examined, repaired);
    }

    /** Food names out of a stored AnalysisResponse, read loosely so one odd row can't fail the migration. */
    private static List<String> foodNames(ObjectMapper mapper, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode foods = mapper.readTree(resultJson).path("foods");
            if (!foods.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>(foods.size());
            for (JsonNode food : foods) {
                String name = food.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("Skipping a row whose result_json could not be read: {}", e.getMessage());
            return List.of();
        }
    }
}
