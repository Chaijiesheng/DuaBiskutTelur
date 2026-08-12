package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LocalFoodRepository extends JpaRepository<LocalFoodEntity, Long> {

    Optional<LocalFoodEntity> findByCanonicalName(String canonicalName);

    /**
     * The same dish reached by one of its other names — "nasi lemak bungkus",
     * "椰浆饭". Written as a join through the alias table rather than a LIKE over
     * a delimited column so both hops are indexed equality lookups; a menu scan
     * runs this up to sixty times per request.
     */
    @Query("""
            select f from LocalFoodEntity f
             where f.id = (select a.localFoodId from LocalFoodAliasEntity a where a.alias = :alias)
            """)
    Optional<LocalFoodEntity> findByAlias(@Param("alias") String alias);
}
