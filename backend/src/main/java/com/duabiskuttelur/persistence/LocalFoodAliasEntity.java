package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Another name the same dish goes by, canonicalized. Malaysian menus mix
 * English, Malay and Chinese for one dish routinely, and the vision model
 * returns whichever the menu printed.
 */
@Entity
@Table(name = "local_food_alias")
public class LocalFoodAliasEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String alias;

    /**
     * Plain id rather than a @ManyToOne. The only query that touches this table
     * resolves an alias to one food and then loads it; an association would add
     * a fetch strategy to reason about for no gain.
     */
    @Column(name = "local_food_id", nullable = false)
    private Long localFoodId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public Long getLocalFoodId() { return localFoodId; }
    public void setLocalFoodId(Long localFoodId) { this.localFoodId = localFoodId; }
}
