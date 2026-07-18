package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Client for the USDA FoodData Central search API. Returns nutrients per 100g
 * for the closest match to a search term. Retries transient failures.
 */
@Component
public class UsdaClient {

    private static final Logger log = LoggerFactory.getLogger(UsdaClient.class);

    /** Nutrients per 100g of the matched food. Sodium in mg, everything else in g/kcal. */
    public record NutrientsPer100g(
            String matchedDescription,
            double calories,
            double protein,
            double carbs,
            double fat,
            double fiber,
            double sugar,
            double sodium
    ) {
    }

    private final AppProperties props;
    private final RestClient restClient;

    public UsdaClient(AppProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(props.getUsdaBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Search FoodData Central for the term and return per-100g nutrients of the
     * top match. Empty when nothing matched or all retries failed.
     */
    public Optional<NutrientsPer100g> lookup(String searchTerm) {
        if (!props.hasUsdaKey()) {
            return Optional.empty();
        }
        int attempts = props.getUsdaRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                JsonNode response = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/fdc/v1/foods/search")
                                .queryParam("api_key", props.getUsdaApiKey())
                                .queryParam("query", searchTerm)
                                .queryParam("pageSize", 1)
                                .queryParam("dataType", "Survey (FNDDS)", "SR Legacy", "Foundation")
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    return Optional.empty();
                }
                JsonNode foods = response.path("foods");
                if (!foods.isArray() || foods.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(parseFood(foods.get(0)));
            } catch (Exception e) {
                log.warn("USDA lookup failed for '{}' (attempt {}/{}): {}", searchTerm, attempt, attempts, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private NutrientsPer100g parseFood(JsonNode food) {
        double calories = 0, protein = 0, carbs = 0, fat = 0, fiber = 0, sugar = 0, sodium = 0;
        for (JsonNode nutrient : food.path("foodNutrients")) {
            int id = nutrient.path("nutrientId").asInt(nutrient.path("nutrient").path("id").asInt());
            double value = nutrient.path("value").asDouble(nutrient.path("amount").asDouble(0));
            switch (id) {
                case 1008 -> calories = value;   // Energy (kcal)
                case 1003 -> protein = value;    // Protein (g)
                case 1005 -> carbs = value;      // Carbohydrate (g)
                case 1004 -> fat = value;        // Total fat (g)
                case 1079 -> fiber = value;      // Fiber (g)
                case 2000 -> sugar = value;      // Total sugars (g)
                case 1093 -> sodium = value;     // Sodium (mg)
                default -> { /* not tracked */ }
            }
        }
        return new NutrientsPer100g(
                food.path("description").asText(""),
                calories, protein, carbs, fat, fiber, sugar, sodium);
    }
}
