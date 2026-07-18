-- Denormalized fields AnalysisService now writes at save time so
-- AchievementsService/DashboardService don't need to re-parse result_json on
-- every read (see MealAnalysisEntity). Nullable: existing rows fall back to
-- parsing result_json until they're re-saved.

ALTER TABLE meal_analysis ADD COLUMN protein DOUBLE PRECISION;
ALTER TABLE meal_analysis ADD COLUMN vegetable_count INTEGER;
ALTER TABLE meal_analysis ADD COLUMN has_fruit BOOLEAN;
ALTER TABLE meal_analysis ADD COLUMN beverage_only BOOLEAN;
ALTER TABLE meal_analysis ADD COLUMN coffee_only BOOLEAN;
