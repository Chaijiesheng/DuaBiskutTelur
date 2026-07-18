package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client for the Open Food Facts product API. No key required. Returns
 * whichever nutrient basis the product actually has data for — per-serving
 * when available, otherwise per-100g — so the caller can scale by whatever
 * the user says they had.
 */
@Component
public class OpenFoodFactsClient {

    private static final Logger log = LoggerFactory.getLogger(OpenFoodFactsClient.class);

    /**
     * Nutrients for one "unit" of the product: a declared serving when the
     * label has one, otherwise 100g. sodium in mg, everything else in g/kcal.
     */
    public record Product(
            String name,
            String unitLabel,
            boolean perServing,
            double calories,
            double protein,
            double carbs,
            double fat,
            double fiber,
            double sugar,
            double sodium,
            List<String> categoryTags
    ) {
    }

    private final RestClient restClient;

    public OpenFoodFactsClient(AppProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(props.getOpenFoodFactsBaseUrl())
                .requestFactory(factory)
                .build();
    }

    public Optional<Product> lookup(String barcode) {
        try {
            JsonNode response = restClient.get()
                    .uri("/api/v2/product/{barcode}.json", barcode)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }
            JsonNode product = response.path("product");
            String name = product.path("product_name").asText("");
            if (product.isMissingNode() || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(parseProduct(product, name));
        } catch (Exception e) {
            log.warn("Open Food Facts lookup failed for '{}': {}", barcode, e.getMessage());
            return Optional.empty();
        }
    }

    private Product parseProduct(JsonNode product, String name) {
        JsonNode n = product.path("nutriments");
        boolean hasServing = n.has("energy-kcal_serving");
        String suffix = hasServing ? "_serving" : "_100g";
        String servingSize = product.path("serving_size").asText(null);
        String unitLabel = hasServing ? (servingSize != null && !servingSize.isBlank() ? servingSize : "1 serving") : "100g";

        List<String> categoryTags = new ArrayList<>();
        for (JsonNode tag : product.path("categories_tags")) {
            categoryTags.add(tag.asText(""));
        }

        return new Product(
                name,
                unitLabel,
                hasServing,
                n.path("energy-kcal" + suffix).asDouble(0),
                n.path("proteins" + suffix).asDouble(0),
                n.path("carbohydrates" + suffix).asDouble(0),
                n.path("fat" + suffix).asDouble(0),
                n.path("fiber" + suffix).asDouble(0),
                n.path("sugars" + suffix).asDouble(0),
                n.path("sodium" + suffix).asDouble(0) * 1000, // OFF reports sodium in g
                categoryTags);
    }
}
