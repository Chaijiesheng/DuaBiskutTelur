package com.duabiskuttelur.model;

/**
 * description and xp are null while locked so the payload itself never leaks
 * the reward text before the user has actually earned it.
 */
public record Badge(String id, String label, String icon, String category,
                     boolean unlocked, boolean secret, String description, Integer xp) {
}
