package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByTicker(String ticker);

    /**
     * Active instruments, biggest first.
     *
     * Written by hand because Spring Data can't do NULLS LAST, and Postgres puts nulls
     * first on DESC. Most instruments have no market cap, so they ended up on top.
     */
    @Query("select i from Instrument i where i.active = true "
         + "order by i.marketCap desc nulls last, i.ticker asc")
    List<Instrument> findByActiveTrueOrderByMarketCapDesc();

    List<Instrument> findBySectorCodeAndActiveTrue(String sectorCode);

    /** All instruments in one query, so ingestion can compare the snapshot in memory. */
    @Query("select i from Instrument i")
    List<Instrument> findAllForSnapshot();

    @Query("""
            select i from Instrument i
            where lower(i.ticker) like lower(concat('%', :q, '%'))
               or lower(i.name)   like lower(concat('%', :q, '%'))
            order by i.marketCap desc nulls last
            """)
    List<Instrument> search(@Param("q") String query);
}
