package com.ofss.entity;
import jakarta.persistence.*;
@Entity @Table(name="PAYMENT_SIMULATION_CONFIG")
public class SimulationConfiguration {
    @Id public Long id;
    @Column(nullable=false) public boolean simulateFailure;
    @Version public Long version;
}
