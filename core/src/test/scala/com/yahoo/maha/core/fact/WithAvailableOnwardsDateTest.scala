// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.core.fact

import com.yahoo.maha.core.CoreSchema._
import com.yahoo.maha.core.FilterOperation.{Equality, In, InEquality}
import com.yahoo.maha.core._
import com.yahoo.maha.core.ddl.HiveDDLAnnotation
import com.yahoo.maha.core.dimension.{DimCol, OracleDerDimCol, PubCol}
import com.yahoo.maha.core.request.{RequestType, SyncRequest}

/**
 * Created by jians on 10/20/15.
 */
class WithAvailableOnwardsDateTest extends BaseFactTest {

  test("withAvailableOnwardsDate: should pass with new engine (Oracle)") {
    val fact = fact1
      ColumnContext.withColumnContext {implicit cc : ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine
          , availableOnwardsDate = Option("2017-09-25")
        )
      }

    val bcOption = publicFact(fact).getCandidatesFor(AdvertiserSchema, SyncRequest, Set("Advertiser Id", "Impressions"), Set.empty, Map("Advertiser Id" -> InFilterOperation), 1, 1, EqualityFilter("Day", s"$toDate"))
    require(bcOption.isDefined, "Failed to get candidates!")
    assert(bcOption.get.facts.values.exists( f => f.fact.name == "fact2") === true)
    assert(bcOption.get.facts.values.find( f => f.fact.name == "fact2").get.fact.forceFilters.isEmpty)
  }

  test("withAvailableOnwardsDate: should pass with redefined FactCol and DimCol") {
    val fact = fact1
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact2", "fact1", HiveEngine,
        overrideDimCols =  Set(
          DimCol("account_id", IntType(), annotations = Set(ForeignKey("cache_advertiser_metadata")))
          , DimCol("campaign_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_group_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_id", IntType(), annotations = Set(ForeignKey("advertiser")))
          , DimCol("stats_source", IntType(3))
          , DimCol("price_type", IntType(3))
          , DimCol("landing_page_url", StrType(), annotations = Set(EscapingRequired))
        ),
        overrideFactCols = Set(
          FactCol("impressions", IntType())
        ), availableOnwardsDate = Option("2017-09-25")
      ).toPublicFact("temp",
        Set(
          PubCol("account_id", "Advertiser Id", InEquality),
          PubCol("stats_source", "Source", Equality),
          PubCol("price_type", "Pricing Type", In),
          PubCol("landing_page_url", "Destination URL", Set.empty)
        ),
        Set(
          PublicFactCol("impressions", "Impressions", InEquality)
        ),
        Set.empty,
        getMaxDaysWindow, getMaxDaysLookBack
      )

    }
    val bcOption = publicFact(fact).getCandidatesFor(AdvertiserSchema, SyncRequest, Set("Impressions"), Set.empty, Map("Advertiser Id" -> InFilterOperation), 1, 1, EqualityFilter("Day", s"$toDate"))
    require(bcOption.isDefined, "Failed to get candidates!")
    assert(bcOption.get.facts.keys.exists(_ == "fact2") === true)
  }

  test("withAvailableOnwardsDate: Should succeed with 2 availableOnwardsDates on the same Engine") {
    val fact = fact1
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine,
        overrideDimCols = Set(
          DimCol("account_id", IntType(), annotations = Set(ForeignKey("cache_advertiser_metadata")))
          , DimCol("campaign_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_group_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_id", IntType(), annotations = Set(ForeignKey("advertiser")))
          , DimCol("stats_source", IntType(3))
          , DimCol("price_type", IntType(3))
          , DimCol("landing_page_url", StrType(), annotations = Set(EscapingRequired))
        ),
        overrideFactCols = Set(
          FactCol("impressions", IntType())
        ), availableOnwardsDate = Option("2017-09-25")
      ).toPublicFact("temp",
        Set(
          PubCol("account_id", "Advertiser Id", InEquality),
          PubCol("stats_source", "Source", Equality),
          PubCol("price_type", "Pricing Type", In),
          PubCol("landing_page_url", "Destination URL", Set.empty)
        ),
        Set(
          PublicFactCol("impressions", "Impressions", InEquality)
        ),
        Set.empty,
        getMaxDaysWindow, getMaxDaysLookBack
      )
    }
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact3", "fact1", OracleEngine,
        overrideDimCols =  Set(
          DimCol("account_id", IntType(), annotations = Set(ForeignKey("cache_advertiser_metadata")))
          , DimCol("campaign_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_group_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_id", IntType(), annotations = Set(ForeignKey("advertiser")))
          , DimCol("stats_source", IntType(3))
          , DimCol("price_type", IntType(3))
          , DimCol("landing_page_url", StrType(), annotations = Set(EscapingRequired))
        ),
        overrideFactCols = Set(
          FactCol("impressions", IntType())
        ), availableOnwardsDate = Option("2017-09-26")
      ).toPublicFact("temp",
        Set(
          PubCol("account_id", "Advertiser Id", InEquality),
          PubCol("stats_source", "Source", Equality),
          PubCol("price_type", "Pricing Type", In),
          PubCol("landing_page_url", "Destination URL", Set.empty)
        ),
        Set(
          PublicFactCol("impressions", "Impressions", InEquality)
        ),
        Set.empty,
        getMaxDaysWindow, getMaxDaysLookBack
      )

    }
    val bcOption = publicFact(fact).getCandidatesFor(AdvertiserSchema, SyncRequest, Set("Advertiser Id", "Impressions"), Set.empty, Map("Advertiser Id" -> InFilterOperation), 1, 1, EqualityFilter("Day", s"$toDate"))
    require(bcOption.isDefined, "Failed to get candidates!")
    assert(bcOption.get.facts.keys.exists(_ == "fact2") === true, "first onwardsDate failed")
    assert(bcOption.get.facts.keys.exists(_ == "fact3") === true, "second onwardsDate failed")
  }

  test("withAvailableOnwardsDate: Should fail with 1 availableOnwardsDate on the same Engine") {
    val fact = fact1
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine,
          overrideDimCols = Set(
            DimCol("account_id", IntType(), annotations = Set(ForeignKey("cache_advertiser_metadata")))
            , DimCol("campaign_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
            , DimCol("ad_group_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
            , DimCol("ad_id", IntType(), annotations = Set(ForeignKey("advertiser")))
            , DimCol("stats_source", IntType(3))
            , DimCol("price_type", IntType(3))
            , DimCol("landing_page_url", StrType(), annotations = Set(EscapingRequired))
          ),
          overrideFactCols = Set(
            FactCol("impressions", IntType())
          ), availableOnwardsDate = Option("2017-09-25")
        ).toPublicFact("temp",
          Set(
            PubCol("account_id", "Advertiser Id", InEquality),
            PubCol("stats_source", "Source", Equality),
            PubCol("price_type", "Pricing Type", In),
            PubCol("landing_page_url", "Destination URL", Set.empty)
          ),
          Set(
            PublicFactCol("impressions", "Impressions", InEquality)
          ),
          Set.empty,
          getMaxDaysWindow, getMaxDaysLookBack
        )
      }
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact3", "fact1", OracleEngine,
          overrideDimCols = Set(
            DimCol("account_id", IntType(), annotations = Set(ForeignKey("cache_advertiser_metadata")))
            , DimCol("campaign_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
            , DimCol("ad_group_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
            , DimCol("ad_id", IntType(), annotations = Set(ForeignKey("advertiser")))
            , DimCol("stats_source", IntType(3))
            , DimCol("price_type", IntType(3))
            , DimCol("landing_page_url", StrType(), annotations = Set(EscapingRequired))
          ),
          overrideFactCols = Set(
            FactCol("impressions", IntType())
          ), availableOnwardsDate = Option("2017-09-25")
        ).toPublicFact("temp",
          Set(
            PubCol("account_id", "Advertiser Id", InEquality),
            PubCol("stats_source", "Source", Equality),
            PubCol("price_type", "Pricing Type", In),
            PubCol("landing_page_url", "Destination URL", Set.empty)
          ),
          Set(
            PublicFactCol("impressions", "Impressions", InEquality)
          ),
          Set.empty,
          getMaxDaysWindow, getMaxDaysLookBack
        )

      }
    }
    thrown.getMessage should startWith ("requirement failed: Base date 2017-09-25 in fact fact3 is already defined in fact2")
  }

  test("withAvailableOnwardsDate: Should succeed with identical availableOnwardsDates on the different Engines") {
    val fact = fact1
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact2", "fact1", HiveEngine,
        overrideDimCols = Set(
          DimCol("account_id", IntType(), annotations = Set(ForeignKey("cache_advertiser_metadata")))
          , DimCol("campaign_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_group_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_id", IntType(), annotations = Set(ForeignKey("advertiser")))
          , DimCol("stats_source", IntType(3))
          , DimCol("price_type", IntType(3))
          , DimCol("landing_page_url", StrType(), annotations = Set(EscapingRequired))
        ),
        overrideFactCols = Set(
          FactCol("impressions", IntType())
        ), availableOnwardsDate = Option("2017-09-25")
      ).toPublicFact("temp",
        Set(
          PubCol("account_id", "Advertiser Id", InEquality),
          PubCol("stats_source", "Source", Equality),
          PubCol("price_type", "Pricing Type", In),
          PubCol("landing_page_url", "Destination URL", Set.empty)
        ),
        Set(
          PublicFactCol("impressions", "Impressions", InEquality)
        ),
        Set.empty,
        getMaxDaysWindow, getMaxDaysLookBack
      )
    }
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact3", "fact1", OracleEngine,
        overrideDimCols =  Set(
          DimCol("account_id", IntType(), annotations = Set(ForeignKey("cache_advertiser_metadata")))
          , DimCol("campaign_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_group_id", IntType(), annotations = Set(ForeignKey("cache_campaign_metadata")))
          , DimCol("ad_id", IntType(), annotations = Set(ForeignKey("advertiser")))
          , DimCol("stats_source", IntType(3))
          , DimCol("price_type", IntType(3))
          , DimCol("landing_page_url", StrType(), annotations = Set(EscapingRequired))
        ),
        overrideFactCols = Set(
          FactCol("impressions", IntType())
        ), availableOnwardsDate = Option("2017-09-25")
      ).toPublicFact("temp",
        Set(
          PubCol("account_id", "Advertiser Id", InEquality),
          PubCol("stats_source", "Source", Equality),
          PubCol("price_type", "Pricing Type", In),
          PubCol("landing_page_url", "Destination URL", Set.empty)
        ),
        Set(
          PublicFactCol("impressions", "Impressions", InEquality)
        ),
        Set.empty,
        getMaxDaysWindow, getMaxDaysLookBack
      )

    }
    val bcOption = publicFact(fact).getCandidatesFor(AdvertiserSchema, SyncRequest, Set("Advertiser Id", "Impressions"), Set.empty, Map("Advertiser Id" -> InFilterOperation), 1, 1, EqualityFilter("Day", s"$toDate"))
    require(bcOption.isDefined, "Failed to get candidates!")
    assert(bcOption.get.facts.keys.exists(_ == "fact2") === true, "first onwardsDate failed")
    assert(bcOption.get.facts.keys.exists(_ == "fact3") === true, "second onwardsDate failed")
  }

  test("withAvailableOnwardsDate: should fail if no tableMap to pull from") {
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc =>
        val base_fact = new FactTable("base_fact", 9999, DailyGrain, OracleEngine, Set(AdvertiserSchema),
          Set(
            DimCol("dimcol1", IntType(), annotations = Set(PrimaryKey))
            , DimCol("dimcol2", IntType())),
          Set(
            FactCol("factcol1", StrType())
          ), None, Set.empty, None, Fact.DEFAULT_COST_MULTIPLIER_MAP,Set.empty,10,100,None, None, None, None)
        val fb = new FactBuilder(base_fact, Map(), None)
        fb.withAvailableOnwardsDate("fact2", "base_fact", OracleEngine
          , availableOnwardsDate = Option("2017-09-25")
        )
      }
    }
    thrown.getMessage should startWith ("requirement failed: no tables found")
  }

  test("withAvailableOnwardsDate: should fail if source fact is wrong") {
    val fact = fact1
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact", OracleEngine
          , availableOnwardsDate = Option("2017-09-25")
        )
      }
    }
    thrown.getMessage should startWith ("requirement failed: from table not found : fact")
  }

  test("withAvailableOnwardsDate: should fail if destination fact name is already in use") {
    val fact = fact1
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine
          , availableOnwardsDate = Option("2017-09-25")
        )
      }
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine
          , availableOnwardsDate = Option("2017-09-30")
        )
      }
    }
    thrown.getMessage should startWith ("requirement failed: should not export with existing table name fact2")
  }

  test("withAvailableOnwardsDate: availableOnwardsDate must be defined") {
    val fact = fact1
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine
        )
      }
    }
    thrown.getMessage should startWith ("requirement failed: availableOnwardsDate parameter must be defined in withAvailableOnwardsDate in fact2")
  }

  test("withAvailableOnwardsDate: should fail if override dim columns have static mapping") {
    val fact = fact1
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext {implicit cc : ColumnContext =>
        import OracleExpression._
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine,
          Set(
            OracleDerDimCol("dimcol1", IntType(3, (Map(1 -> "One"), "NONE")), DECODE_DIM("{dimcol1}", "0", "zero", "{dimcol1}"))
          ), availableOnwardsDate = Option("2017-09-30")
        )
      }
    }
    thrown.getMessage should startWith ("requirement failed: Override column cannot have static mapping")
  }

  test("withAvailableOnwardsDate: should fail if override fact columns have static mapping") {
    val fact = fact1
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext {implicit cc : ColumnContext =>
        import OracleExpression._
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine,
          overrideFactCols = Set(
            OracleDerFactCol("factcol1", IntType(3, (Map(1 -> "One"), "NONE")), SUM("{dimcol1}"))
          ), availableOnwardsDate = Option("2017-09-30")
        )
      }
    }
    thrown.getMessage should startWith ("requirement failed: Override column cannot have static mapping")
  }

  test("withAvailableOnwardsDate: should discard the new table if the availableOnwardsDate > requested date") {
    val fact = fact1
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine, availableOnwardsDate = Some(s"$toDate"))
    }
    val bcOption = publicFact(fact).getCandidatesFor(AdvertiserSchema, SyncRequest, Set("Advertiser Id", "Impressions"), Set.empty, Map("Advertiser Id" -> InFilterOperation), 1, 1, EqualityFilter("Day", s"$fromDate"))
    require(bcOption.isDefined, "Failed to get candidates!")
    assert(bcOption.get.facts.keys.exists(_ == "fact2") === false, "should discard best candidate of new rollup based on the availableOnwardsDate")
  }

  test("withAvailableOnwardsDate: should fail with missing override for fact column annotation with engine requirement") {
    val fact = fact1WithAnnotationWithEngineRequirement
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine, overrideFactCols = Set(
        ), availableOnwardsDate = Option("2017-09-30"))
      }
    }
    thrown.getMessage should include("missing fact annotation overrides = List((clicks,Set(HiveSnapshotTimestamp))),")
  }

  test("withAvailableOnwardsDate: should fail with missing override for fact column rollup with engine requirement") {
    val fact = fact1WithRollupWithEngineRequirement
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine, overrideFactCols = Set(
        ), availableOnwardsDate = Option("2017-09-30"))
      }
    }
    thrown.getMessage should include("missing fact rollup overrides = List((clicks,HiveCustomRollup(")
  }

  test("withAvailableOnwardsDate: should fail if ddl annotation is of a different engine other than the engine of the fact") {
    val fact = fact1
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit  cc : ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine, overrideDDLAnnotation = Option(HiveDDLAnnotation()), availableOnwardsDate = Option("2017-09-30"))
      }
    }
    thrown.getMessage should startWith ("requirement failed: Failed engine requirement fact=fact2, engine=Oracle, ddlAnnotation=Some(HiveDDLAnnotation(Map(),Vector()))")
  }

  test("withAvailableOnwardsDate: column annotation override success case") {
    val fact = fact1WithAnnotationWithEngineRequirement
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine
        , overrideFactCols = Set(
          FactCol("clicks", IntType(), annotations = Set(EscapingRequired))
        )
      , availableOnwardsDate = Option("2017-09-30"))
    }
    val bcOption = publicFact(fact).getCandidatesFor(AdvertiserSchema, SyncRequest, Set("Advertiser Id", "Impressions"), Set.empty, Map("Advertiser Id" -> InFilterOperation), 1, 1, EqualityFilter("Day", s"$toDate"))
    require(bcOption.isDefined, "Failed to get candidates!")
    assert(bcOption.get.facts.values.exists( f => f.fact.name == "fact2") === true)
    assert(bcOption.get.facts.values.find( f => f.fact.name == "fact2").get.fact.forceFilters.isEmpty)
  }

  test("withAvailableOnwardsDate: dim column override failure case") {
    val fact = factDerivedWithFailingDimCol
    val thrown = intercept[IllegalArgumentException] {
      ColumnContext.withColumnContext { implicit cc: ColumnContext =>
        fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine
          , availableOnwardsDate = Option("2017-09-30"))
      }
    }
    thrown.getMessage should startWith ("requirement failed: name=fact2, from=fact1, engine=Oracle, missing dim overrides = List((price_type,Set(HiveSnapshotTimestamp)))")
  }

  test("withAvailableOnwardsDate: dim column override success case") {
    val fact = factDerivedWithFailingDimCol
    ColumnContext.withColumnContext { implicit cc: ColumnContext =>
      fact.withAvailableOnwardsDate("fact2", "fact1", OracleEngine,
        overrideDimCols = Set(
            DimCol("price_type", IntType(), annotations = Set.empty)
        )
        , availableOnwardsDate = Option("2017-09-30"))
    }
    val bcOption = publicFact(fact).getCandidatesFor(AdvertiserSchema, SyncRequest, Set("Advertiser Id", "Impressions"), Set.empty, Map("Advertiser Id" -> InFilterOperation), 1, 1, EqualityFilter("Day", s"$toDate"))
    require(bcOption.isDefined, "Failed to get candidates!")
    assert(bcOption.get.facts.values.exists( f => f.fact.name == "fact2") === true)
    assert(bcOption.get.facts.values.find( f => f.fact.name == "fact2").get.fact.forceFilters.isEmpty)
  }
}