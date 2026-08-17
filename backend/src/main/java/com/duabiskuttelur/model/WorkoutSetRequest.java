package com.duabiskuttelur.model;

/**
 * Marks one set done or not done.
 *
 * <p>The request states the intended <em>result</em> ({@code done}) rather than
 * an action ("increment"), which is what lets the client replay a queue of these
 * after being offline without having to know which ones already landed. The
 * unique constraint on {@code workout_set_log} finishes the job.
 */
public record WorkoutSetRequest(Integer exercisePosition, Integer setIndex, Boolean done) {
}
