package com.duabiskuttelur.client;

import com.duabiskuttelur.model.IdentifiedFood;

import java.util.List;

/**
 * Provider-agnostic vision analysis: identify foods and portion estimates from
 * a meal photo. Implementations wrap a specific AI provider so the service
 * layer never depends on one vendor's wire format.
 */
public interface VisionAnalysisClient {

    List<IdentifiedFood> identifyFoods(byte[] imageBytes, String mediaType);

    /** Reads a restaurant/cafe menu photo (a list of dishes, not a plated meal) and returns one entry per distinct dish. */
    List<IdentifiedFood> identifyMenuDishes(byte[] imageBytes, String mediaType);
}
