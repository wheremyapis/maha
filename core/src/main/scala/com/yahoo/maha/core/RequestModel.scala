// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.core

import com.yahoo.maha.core
import com.yahoo.maha.core.bucketing.{BucketParams, BucketSelector, CubeBucketSelected}
import com.yahoo.maha.core.dimension.PublicDimension
import com.yahoo.maha.core.fact.{BestCandidates, PublicFact, PublicFactCol, PublicFactColumn}
import com.yahoo.maha.core.registry.{FactRowsCostEstimate, Registry}
import com.yahoo.maha.core.request.Parameter.Distinct
import com.yahoo.maha.core.request._
import com.yahoo.maha.core.error._
import com.yahoo.maha.core.query.{InnerJoin, JoinType, LeftOuterJoin, RightOuterJoin}
import com.yahoo.maha.utils.DaysUtils
import grizzled.slf4j.Logging
import org.slf4j.LoggerFactory

import scala.collection.{SortedSet, mutable}
import scala.util.{Failure, Success, Try}

/**
 * Created by jians on 10/5/15.
 */
case class DimensionCandidate(dim: PublicDimension
                              , fields: Set[String]
                              , filters: SortedSet[Filter]
                              , upperCandidates: List[PublicDimension]
                              , lowerCandidates: List[PublicDimension]
                              , isDrivingDimension: Boolean
                              , hasNonFKOrForcedFilters: Boolean
                              , hasNonFKNonForceFilters: Boolean
                              , hasNonFKSortBy: Boolean
                              , hasNonFKNonPKSortBy: Boolean
                              , hasLowCardinalityFilter: Boolean
                              , hasPKRequested : Boolean
                              , hasNonPushDownFilters : Boolean
                             ) {

  def debugString : String = {
    s"""
       fields=$fields
       filters=${filters.map(_.field)}
       upperCandidates=${upperCandidates.map(_.name)}
       lowerCandidates=${lowerCandidates.map(_.name)}
       isDrivingDimension=$isDrivingDimension
       hasNonFKOrForcedFilters=$hasNonFKOrForcedFilters
       hasNonFKNonForceFilters=$hasNonFKNonForceFilters
       hasNonFKSortBy=$hasNonFKSortBy
       hasNonFKNonPKSortBy=$hasNonFKNonPKSortBy
       hasLowCardinalityFilter=$hasLowCardinalityFilter
       hasPKRequested=$hasPKRequested
       hasNonPushDownFilters=$hasNonPushDownFilters
     """
  }
}
case class DimensionRelations(relations: Map[(String, String), Boolean]) {
  val hasUnrelatedDimensions: Boolean = relations.exists(!_._2)
}

object DimensionCandidate {
  implicit val ordering: Ordering[DimensionCandidate] = Ordering.by(dc => s"${dc.dim.dimLevel.level}-${dc.dim.name}")
}

sealed trait ColumnInfo {
  def alias: String
}
case class FactColumnInfo(alias: String) extends ColumnInfo
case class DimColumnInfo(alias: String) extends ColumnInfo
case class ConstantColumnInfo(alias: String, value: String) extends ColumnInfo

sealed trait RequestCol {
  def alias: String
  def isKey: Boolean
  def isJoinKey: Boolean
  final override def hashCode: Int = alias.hashCode
  final override def equals(other: Any) : Boolean = {
    if(other == null) return false
    other match {
      case rc: RequestCol =>
        alias.equals(rc.alias)
      case _ => false
    }
  }
  def toName(name: String) : RequestCol
}
object RequestCol {
  implicit val ordering: Ordering[RequestCol] = Ordering.by(_.alias)
}

object BaseRequestCol {
  def apply(s: String) : BaseRequestCol = new BaseRequestCol(s)
}
class BaseRequestCol(val alias: String) extends RequestCol {
  def isKey : Boolean = false
  def isJoinKey: Boolean = false
  def toName(name: String) : RequestCol = new BaseRequestCol(name)
}
class JoinKeyCol(val alias: String) extends RequestCol {
  def isKey : Boolean = true
  def isJoinKey: Boolean = true
  def toName(name: String) : RequestCol = new JoinKeyCol(name)
}

trait SortByColumnInfo {
  def alias: String
  def order: Order
}

case class DimSortByColumnInfo(alias: String, order: Order) extends SortByColumnInfo
case class FactSortByColumnInfo(alias: String, order: Order) extends SortByColumnInfo

case class RequestModel(cube: String
                        , bestCandidates: Option[BestCandidates]
                        , factFilters: SortedSet[Filter]
                        , dimensionsCandidates: SortedSet[DimensionCandidate]
                        , requestCols: IndexedSeq[ColumnInfo]
                        , requestSortByCols: IndexedSeq[SortByColumnInfo]
                        , dimColumnAliases: Set[String]
                        , dimCardinalityEstimate: Option[Long]
                        , factCost: Map[(String, Engine), FactRowsCostEstimate]
                        , factSortByMap : Map[String, Order]
                        , dimSortByMap : Map[String, Order]
                        , hasFactFilters: Boolean
                        , hasMetricFilters: Boolean
                        , hasNonFKFactFilters: Boolean
                        , hasDimFilters: Boolean
                        , hasNonFKDimFilters: Boolean
                        , hasFactSortBy: Boolean
                        , hasDimSortBy: Boolean
                        , isFactDriven: Boolean
                        , forceDimDriven: Boolean
                        , forceFactDriven: Boolean
                        , hasNonDrivingDimSortOrFilter: Boolean
                        , hasDrivingDimNonFKNonPKSortBy: Boolean
                        , hasNonDrivingDimNonFKNonPKFilter: Boolean
                        , anyDimHasNonFKNonForceFilter: Boolean
                        , schema: Schema
                        , utcTimeDayFilter: Filter
                        , localTimeDayFilter: Filter
                        , utcTimeHourFilter: Option[Filter]
                        , localTimeHourFilter: Option[Filter]
                        , utcTimeMinuteFilter: Option[Filter]
                        , localTimeMinuteFilter: Option[Filter]
                        , requestType: RequestType
                        , startIndex: Int
                        , maxRows: Int
                        , includeRowCount: Boolean
                        , isDebugEnabled: Boolean
                        , additionalParameters : Map[Parameter, Any]
                        , factSchemaRequiredAliasesMap: Map[String, Set[String]]
                        , reportingRequest: ReportingRequest
                        , queryGrain: Option[Grain]=Option.apply(DailyGrain)
                        , isRequestingDistict:Boolean
                        , hasLowCardinalityDimFilters: Boolean
                        , requestedDaysWindow: Int
                        , requestedDaysLookBack: Int
                        , outerFilters: SortedSet[Filter]
                        , requestedFkAliasToPublicDimensionMap: Map[String, PublicDimension]
                        , orFilterMeta: Set[OrFilterMeta]
                        , dimensionRelations: DimensionRelations
  ) {

  val requestColsSet: Set[String] = requestCols.map(_.alias).toSet
  lazy val dimFilters: SortedSet[Filter] = dimensionsCandidates.flatMap(_.filters)

  def isDimDriven: Boolean = !isFactDriven
  def hasDimAndFactOperations: Boolean = (hasNonFKDimFilters || hasDimSortBy) && (hasNonFKFactFilters || hasFactSortBy)
  def isTimeSeries: Boolean = requestCols.exists(ci => Grain.grainFields(ci.alias))
  def isAsyncRequest : Boolean = requestType == AsyncRequest
  def isSyncRequest : Boolean = requestType == SyncRequest
  def forceQueryEngine: Option[Engine] = additionalParameters.get(Parameter.QueryEngine).map(_.asInstanceOf[QueryEngineValue].value)

  /*
  Map to store the dimension name to JoinType associated with the given dimension based on the different constraints.
  defaultJoinType has higher preference than the joinType associated with the dimensions.
   */
  val publicDimToJoinTypeMap : Map[String, JoinType]  = {
    //dim driven query
    //1. fact ROJ driving dim (filter or no filter)
    //2. fact ROJ driving dim (filter or no filter) LOJ parent dim LOJ parent dim
    //3. fact ROJ driving dim IJ parent dim IJ parent dim
    //4. fact IJ driving dim [IJ parent dim IJ parent dim] (metric filter)
    //fact driven query
    //1. fact LOJ driving dim (no filter)
    //2. fact LOJ driving dim (no filter) LOJ parent dim (no filter) LOJ parent dim (no filter)
    //3. fact IJ driving dim (filter on anything)
    //4. fact IJ driving dim IJ parent dim IJ parent dim

    val schema: Schema = reportingRequest.schema
    val anyDimsHasSchemaRequiredNonKeyField: Boolean = dimensionsCandidates.exists(
      _.dim.schemaRequiredAlias(schema).exists(!_.isKey))
    dimensionsCandidates.map {
      dc =>
        //driving dim case
        if(dc.isDrivingDimension) {
          val joinType = if(forceDimDriven) {
            if(hasMetricFilters) {
              InnerJoin
            } else {
              RightOuterJoin
            }
          } else {
            if(anyDimHasNonFKNonForceFilter || anyDimsHasSchemaRequiredNonKeyField) {
              InnerJoin
            } else {
              LeftOuterJoin
            }

          }
          dc.dim.name -> joinType
        } else {
          //non driving dim case
          val joinType = if(forceDimDriven) {
              InnerJoin
          } else {
            if(anyDimHasNonFKNonForceFilter || anyDimsHasSchemaRequiredNonKeyField) {
              InnerJoin
            } else {
              LeftOuterJoin
            }
          }
          dc.dim.name -> joinType
        }
    }.toMap
  }

  utcTimeDayFilter match {
    case BetweenFilter(field, from, to) =>
      require(!DaysUtils.isFutureDate(from), FutureDateNotSupportedError(from))
    case EqualityFilter(field, date, _, _) =>
      require(!DaysUtils.isFutureDate(date), FutureDateNotSupportedError(date))
    case InFilter(field, dates, _, _) =>
      require(!dates.forall(date => DaysUtils.isFutureDate(date)), FutureDateNotSupportedError(dates.mkString(", ")))
    case a =>
      throw new IllegalArgumentException(s"Filter operation not supported. Day filter can be between, in, equality filter : $a")
  }

  def getMostRecentRequestedDate() : String = {
    val mostRecentDate: String = {
      utcTimeDayFilter match {
        case EqualityFilter(field, date, _, _) => date
        case BetweenFilter(field, from, to) => to
        case InFilter(field, dates, _, _) =>
          var minDiff : Integer = scala.Int.MaxValue
          var answer : String = null
          dates.foreach { date =>
            val curDiff = DailyGrain.getDaysFromNow(date)
            if (curDiff < minDiff) {
              minDiff = curDiff
              answer = date
            }
          }
          answer
        case _ =>
          throw new IllegalArgumentException(s"Filter operation not supported. Day filter can be between, in, equality filter.")
      }
    }
    mostRecentDate
  }

  def debugString : String = {
    s"""
       cube=$cube
       requestCols=$requestCols
       requestSortByCols=$requestSortByCols
       dimColumnAliases=$dimColumnAliases
       dimCardinalityEstimate=$dimCardinalityEstimate
       factCost=$factCost
       factSortByMap=$factSortByMap
       dimSortByMap=$dimSortByMap
       hasFactFilters=$hasFactFilters
       hasMetricFilters=$hasMetricFilters
       hasNonFKFactFilters=$hasNonFKFactFilters
       hasDimFilters=$hasDimFilters
       hasNonFKDimFilters=$hasNonFKDimFilters
       hasFactSortBy=$hasFactSortBy
       hasDimSortBy=$hasDimSortBy
       hasNonDrivingDimSortFilter=$hasNonDrivingDimSortOrFilter
       hasDrivingDimNonFKNonPKSortBy=$hasDrivingDimNonFKNonPKSortBy
       hasNonDrivingDimNonFKNonPKFilter=$hasNonDrivingDimNonFKNonPKFilter
       anyDimHasNonFKNonForceFilter=$anyDimHasNonFKNonForceFilter
       hasLowCardinalityDimFilters=$hasLowCardinalityDimFilters
       isFactDriven=$isFactDriven
       forceDimDriven=$forceDimDriven
       schema=$schema
       utcTimeDayFilter=$utcTimeDayFilter
       localTimeDayFilter=$localTimeDayFilter
       requestType=$requestType
       startIndex=$startIndex
       maxRows=$maxRows
       includeRowCount=$includeRowCount
       hasDimAndFactOperations=$hasDimAndFactOperations
       isTimeSeries=$isTimeSeries
       isDebugEnabled=$isDebugEnabled
       additionalParameters=$additionalParameters
       factSchemaRequiredAliasesMap=$factSchemaRequiredAliasesMap
       queryGrain=$queryGrain
       isRequestingDistict=$isRequestingDistict
       publicDimToJoinTypeMap=$publicDimToJoinTypeMap
       dimensionsCandidates=${dimensionsCandidates.map(_.debugString)}
     """
  }
}

object RequestModel extends Logging {
  private[this] val MAX_ALLOWED_STR_LEN = 3999: Int
  def max_allowed_str_len: Int = MAX_ALLOWED_STR_LEN

  val Logger = LoggerFactory.getLogger(classOf[RequestModel])

  def from(request: ReportingRequest, registry: Registry, utcTimeProvider: UTCTimeProvider = PassThroughUTCTimeProvider, revision: Option[Int] = None) : Try[RequestModel] = {
    Try {
      registry.getFact(request.cube, revision) match {
        case None =>
          throw new IllegalArgumentException(s"cube does not exist : ${request.cube}")
        case Some(publicFact) =>
          val fieldMap = new mutable.HashMap[String, Field]()
          // all non-constant fields from request
          val allRequestedAliases = new mutable.TreeSet[String]()
          val allRequestedFactAliases = new mutable.TreeSet[String]()
          val allRequestedFactJoinAliases = new mutable.TreeSet[String]()
          val allRequestedDimensionPrimaryKeyAliases = new mutable.TreeSet[String]()
          val allRequestedNonFactAliases = new mutable.TreeSet[String]()
          val allDependentColumns = new mutable.TreeSet[String]()
          val allProjectedAliases = request.selectFields.map(f=> f.field).toSet

          // populate all requested fields into allRequestedAliases
          request.selectFields.view.filter(field => field.value.isEmpty).foreach { field =>
            allRequestedAliases += field.field
            fieldMap.put(field.field, field)
            //add dependent column
            if(publicFact.dependentColumns(field.field)) {
              allDependentColumns += field.field
            }
          }

          val queryGrain:Option[Grain]= if(fieldMap.contains(HourlyGrain.HOUR_FILTER_FIELD)){
            Option.apply(HourlyGrain)
          } else {
            Option.apply(DailyGrain)
          }
          val isDebugEnabled = request.isDebugEnabled
          val localTimeMinuteFilter = request.minuteFilter
          val localTimeHourFilter = request.hourFilter
          val localTimeDayFilter = request.dayFilter
          val maxDaysWindowOption = publicFact.maxDaysWindow.get(request.requestType, queryGrain.getOrElse(DailyGrain))
          val maxDaysLookBackOption = publicFact.maxDaysLookBack.get(request.requestType, queryGrain.getOrElse(DailyGrain))
          require(maxDaysLookBackOption.isDefined && maxDaysWindowOption.isDefined
            , GranularityNotSupportedError(request.cube, request.requestType, queryGrain.getOrElse(DailyGrain)))
          val maxDaysWindow = maxDaysWindowOption.get
          val maxDaysLookBack = maxDaysLookBackOption.get

          // validating max lookback againt public fact a
          val (requestedDaysWindow, requestedDaysLookBack) = validateMaxLookBackWindow(localTimeDayFilter, publicFact.name, maxDaysWindow, maxDaysLookBack)
          val isAsyncFactDrivenQuery = request.requestType == AsyncRequest && !request.forceDimensionDriven
          val isSyncFactDrivenQuery = request.requestType == SyncRequest && request.forceFactDriven

          val constantValueFields: Set[String] = request.selectFields
            .filter(field => field.value.isDefined)
            .map(field => field.alias.getOrElse(field.field))
            .toSet

          val allRequestedFkAliasToPublicDimMap =
            publicFact.foreignKeyAliases.filter(allProjectedAliases.contains(_)).map {
              case fkAlias =>
               val dimensionOption = registry.getDimensionByPrimaryKeyAlias(fkAlias, revision)
                require(dimensionOption.isDefined, s"Can not find the dimension for Foreign Key Alias $fkAlias in public fact ${publicFact.name}")
                fkAlias -> dimensionOption.get
            }.toMap

          // Check for duplicate aliases/fields
          // User is allowed to ask same column with different alias names
          val requestedAliasList: IndexedSeq[String] = request.selectFields.map(field => field.alias.getOrElse(field.field))
          val duplicateAliases: StringBuilder = new StringBuilder
          requestedAliasList.diff(requestedAliasList.distinct).distinct.foreach(alias => duplicateAliases.append(alias).append(","))

          require(requestedAliasList.distinct.size == requestedAliasList.size,
            s"Duplicate fields/aliases found: cube=${publicFact.name}, duplicate fields are $duplicateAliases")

          /* For fields
          1. Identify all fact cols, and all remaining cols
          2. Check all remaining cols if they have primary key alias's
          3. If any remaining column has no primary key, fail
          4. For all primary key's identified, check if fact has them, if not, fail
          5. If all primary key's found, add them to set of all requested fields (all fields + all primary key alias's),
             and all requested fact fields (all fact cols + all primary key alias's) and all requested dimensions
          */

          /* For filters
          1. Identify all fact filters, and all remaining filters
          2. Check all remaining filters if they have primary key
          3. If any remaining filter has no primary key, fail
          4. For all primary key's identified, check if fact has them, if not, fail
          5. If all primary key's found, add them to set of all requested fields,  requested fact fields,
             and all requested dimensions
           */

          /* For order by
          1. Check all order by fields are in all requested fields, if not, fail
           */

          /* Generate Best Candidates
          1. Given all requested fact cols and optional schema, generate best candidates
          2. Check all fact cols, there must be at least 1 FactCol
          3. If none found, fail
           */

          /* Generate Dimension Candidates
          1. Given all requested dimensions, generate dimension candidates
           */

          /* Check if it is dim driven
          1. If there's no fact filter or fact ordering, then it is dimDriven
           */

          //for dim driven only, list of primary keys
          val dimDrivenRequestedDimensionPrimaryKeyAliases = new mutable.TreeSet[String]()


          //check required aliases
          publicFact.requiredAliases.foreach {
            alias =>
              require(fieldMap.contains(alias), s"Missing required field: cube=${publicFact.name}, field=$alias")
          }

          //check dependent columns
          allDependentColumns.foreach {
            alias =>
              publicFact.columnsByAliasMap(alias).dependsOnColumns.foreach {
                dependentAlias =>
                  require(fieldMap.contains(dependentAlias),
                    s"Missing dependent column : cube=${publicFact.name}, field=$alias, depensOnColumn=$dependentAlias")
              }
          }

          val colsWithRestrictedSchema: IndexedSeq[String] = requestedAliasList.collect {
            case reqCol if (publicFact.restrictedSchemasMap.contains(reqCol) && !publicFact.restrictedSchemasMap(reqCol)(request.schema)) => reqCol
          }
          require(colsWithRestrictedSchema.isEmpty, RestrictedSchemaError(colsWithRestrictedSchema, request.schema.entryName, publicFact.name))

          // separate into fact cols and all remaining non fact cols
          allRequestedAliases.foreach { field =>
            if(publicFact.columnsByAlias(field)) {
              if(publicFact.foreignKeyAliases(field) && !isAsyncFactDrivenQuery) {
                dimDrivenRequestedDimensionPrimaryKeyAliases += field
                allRequestedNonFactAliases += field
              }
              allRequestedFactAliases += field
            } else {
              allRequestedNonFactAliases += field
            }
          }

          // get all primary key aliases for non fact cols
          allRequestedNonFactAliases.foreach { field =>
            if(registry.isPrimaryKeyAlias(field) && publicFact.columnsByAlias(field)) {
              //it's a key column we already have, no op
            } else {
              val primaryKeyAliasOption = registry.getPrimaryKeyAlias(publicFact.name, revision, field)
              require(primaryKeyAliasOption.isDefined, UnknownFieldNameError(field))
              require(publicFact.columnsByAlias(primaryKeyAliasOption.get),
                NoRelationWithPrimaryKeyError(request.cube, primaryKeyAliasOption.get, Option(field)))
              allRequestedAliases += primaryKeyAliasOption.get
              allRequestedFactAliases += primaryKeyAliasOption.get
              allRequestedFactJoinAliases += primaryKeyAliasOption.get
              allRequestedDimensionPrimaryKeyAliases += primaryKeyAliasOption.get
            }
          }

          publicFact.incompatibleColumns.foreach {
            case (alias, incompatibleColumns) =>
              require(!(allRequestedFactAliases.contains(alias)  && !incompatibleColumns.intersect(allRequestedAliases).isEmpty),
                InCompatibleColumnError(alias, incompatibleColumns))
          }

          //keep map from alias to filter for final map back to Set[Filter]
          var filterMap = new mutable.HashMap[String, Filter]()
          var pushDownFilterMap = new mutable.HashMap[String, PushDownFilter]()
          var allFilterAliases = new mutable.TreeSet[String]()
          val allFactFilters = new mutable.TreeSet[Filter]()
          val allNonFactFilterAliases = new mutable.TreeSet[String]()
          val allOuterFilters = mutable.TreeSet[Filter]()
          val allOrFilterMeta = mutable.Set[OrFilterMeta]()

          // populate all filters into allFilterAliases
          request.filterExpressions.foreach { filter =>
            val (filterMapSingle, allFilterAliasesSingle, allOuterFiltersSingle, allOrFilterMetaSingle) = validateAndReturnFilterData(filter, allRequestedAliases.toSet, publicFact)
            filterMap ++= filterMapSingle
            allFilterAliases ++= allFilterAliasesSingle
            allOuterFilters ++= allOuterFiltersSingle
            allOrFilterMeta ++= allOrFilterMetaSingle
          }

          //check required filter aliases
          publicFact.requiredFilterAliases.foreach {
            alias =>
              require(filterMap.contains(alias), s"Missing required filter: cube=${publicFact.name}, field=$alias")
          }

          // populate all forced filters from fact
          val (allFilterAliasesResult, filterMapResult) = populateFiltersFromFactForcedFilters(publicFact, allFilterAliases.toSet, filterMap.toMap)
          allFilterAliases = mutable.TreeSet[String](allFilterAliasesResult.toList:_*)
          filterMap =  mutable.HashMap[String, Filter](filterMapResult.toSeq:_*)

          //list of fk filters
          val filterPostProcess = new mutable.TreeSet[String]
          // separate into fact filters and all remaining non fact filters, except fact filters which are foreign keys
          allFilterAliases.foreach { filter =>
            if(publicFact.columnsByAlias(filter)) {
              if(publicFact.foreignKeyAliases(filter)) {
                //we want to process these after all non foreign keys have been processed
                filterPostProcess += filter
              }
              allFactFilters += filterMap(filter)
            } else {
              allNonFactFilterAliases += filter
            }
          }

          val allFactSortBy = new mutable.HashMap[String, Order]
          val allDimSortBy = new mutable.HashMap[String, Order]

          var orderingPostProcess = List.empty[SortBy]
          //process all non foreign key / primary key sort by's
          request.sortBy.foreach {
            ordering =>
              val primaryKeyAlias = registry.getPrimaryKeyAlias(publicFact.name, ordering.field)
              if(primaryKeyAlias.isDefined) {
                allDimSortBy.put(ordering.field, ordering.order)
              } else {
                require(publicFact.columnsByAlias(ordering.field), s"Failed to determine dim or fact source for ordering by ${ordering.field}")
                if(publicFact.foreignKeyAliases(ordering.field)) {
                  //skip as we want to process these after all non foreign keys have been processed
                  orderingPostProcess ::= ordering
                } else {
                  allFactSortBy.put(ordering.field, ordering.order)
                }
              }
          }

          //primary key alias in the allNonFactFilterAliases should never occur unless does not exist in public fact
          allNonFactFilterAliases.foreach { filter =>
            if(registry.isPrimaryKeyAlias(filter)) {
              require(publicFact.columnsByAlias(filter),
                NoRelationWithPrimaryKeyError(request.cube, filter))
            } else {
              val primaryKeyAliasOption = registry.getPrimaryKeyAlias(publicFact.name, revision, filter)
              require(primaryKeyAliasOption.isDefined, UnknownFieldNameError(filter))
              require(publicFact.columnsByAlias(primaryKeyAliasOption.get),
                NoRelationWithPrimaryKeyError(request.cube, primaryKeyAliasOption.get, Option(filter)))
              allRequestedAliases += primaryKeyAliasOption.get
              allRequestedFactAliases += primaryKeyAliasOption.get
              allRequestedFactJoinAliases += primaryKeyAliasOption.get
              allRequestedDimensionPrimaryKeyAliases += primaryKeyAliasOption.get
            }
          }

          // ordering fields must be in requested fields
          request.sortBy.foreach {
            ordering => require(fieldMap.contains(ordering.field), s"Ordering fields must be in requested fields : ${ordering.field}")
          }

          //if all fact aliases and fact filters are all pk aliases, then it must be dim only query
          if(allRequestedFactAliases.forall(a => allRequestedNonFactAliases(a) || registry.isPrimaryKeyAlias(a)) && checkAllFactFiltersArePKAliases(allFactFilters.toSet, registry)) {
            //clear fact aliases
            allRequestedFactAliases.clear()
            allRequestedFactJoinAliases.clear()
            //clear fact filters
            allFactFilters.clear()
          }


          //validate filter operation on fact filter field
          validateAllTabularFilters(allFactFilters.toSet, publicFact)

          val bestCandidatesOption: Option[BestCandidates] = if(allRequestedFactAliases.nonEmpty || allFactFilters.nonEmpty) {
            for {
              bestCandidates <- publicFact.getCandidatesFor(
                request.schema
                , request.requestType
                , allRequestedFactAliases.toSet
                , allRequestedFactJoinAliases.toSet
                , createAllFilterMap(allFactFilters.toSet)
                , requestedDaysWindow
                , requestedDaysLookBack
                , localTimeDayFilter)
            } yield bestCandidates
          } else None

          //if there are no fact cols or filters, we don't need best candidate, otherwise we do
          require((allRequestedFactAliases.isEmpty && allFactFilters.isEmpty)
            || (bestCandidatesOption.isDefined && bestCandidatesOption.get.facts.nonEmpty)
            , s"No fact best candidates found for request, fact cols : $allRequestedAliases, fact filters : ${returnFieldSetOnMultipleFiltersWithoutValidation(allFactFilters.toSet)}")

          //val bestCandidates = bestCandidatesOption.get

          //keep entitySet for cost estimation for schema specific entities
          val factToEntitySetMap : mutable.Map[(String, Engine), Set[String]] = new mutable.HashMap
          val entityPublicDimSet = mutable.TreeSet[PublicDimension]()

          val factSchemaRequiredAliasesMap = new mutable.HashMap[String, Set[String]]
          bestCandidatesOption.foreach(_.facts.values.foreach {
            factCandidate =>
              val fact = factCandidate.fact
              //check schema required aliases for facts
              val schemaRequiredFilterAliases = registry.getSchemaRequiredFilterAliasesForFact(fact.name, request.schema, publicFact.name)
              val entitySet = schemaRequiredFilterAliases.map(f => registry.getDimensionByPrimaryKeyAlias(f, Option.apply(publicFact.dimRevision))).flatten.map {
                publicDim =>
                  entityPublicDimSet += publicDim
                  publicDim.name
              }

              val missingFields = schemaRequiredFilterAliases.filterNot(allFilterAliases.apply)
              require(missingFields.isEmpty,
                s"required filter for cube=${publicFact.name}, schema=${request.schema}, fact=${fact.name} not found = $missingFields , found = $allFilterAliases")
              factToEntitySetMap.put(fact.name -> fact.engine, entitySet)
              factSchemaRequiredAliasesMap.put(fact.name, schemaRequiredFilterAliases)
          })

          val timezone = if(bestCandidatesOption.isDefined && bestCandidatesOption.get.publicFact.enableUTCTimeConversion) {
            utcTimeProvider.getTimezone(request)
          } else None

          val (utcTimeDayFilter, utcTimeHourFilter, utcTimeMinuteFilter) = utcTimeProvider.getUTCDayHourMinuteFilter(localTimeDayFilter, localTimeHourFilter, localTimeMinuteFilter, timezone, isDebugEnabled)

          //set fact flags
          //we don't count fk filters here
          val hasNonFKFactFilters = hasNonFKFactFiltersChecker(allFactFilters.toSet, filterPostProcess.toSet)
          val hasFactFilters = allFactFilters.nonEmpty
          val hasMetricFilters = if(bestCandidatesOption.isDefined) {
            val bestCandidates = bestCandidatesOption.get
            val publicFact = bestCandidates.publicFact
            checkIfHasMetricFilters(allFactFilters.toSet, publicFact)
          } else false

          //we have to post process since the order of the sort by item could impact if conditions
          //let's add fact sort by's first
          orderingPostProcess.foreach {
            ordering =>
              //if we are fact driven, add to fact sort by else add to dim sort by
              if (allFactSortBy.nonEmpty || (isAsyncFactDrivenQuery && allRequestedDimensionPrimaryKeyAliases.isEmpty)) {
                allFactSortBy.put(ordering.field, ordering.order)
              } else {
                if(allRequestedDimensionPrimaryKeyAliases.contains(ordering.field)
                  || (request.requestType == SyncRequest && !request.forceFactDriven)
                  || (request.requestType == AsyncRequest && request.forceDimensionDriven)) {
                  allDimSortBy.put(ordering.field, ordering.order)
                } else if(allRequestedFactAliases.contains(ordering.field)) {
                  allFactSortBy.put(ordering.field, ordering.order)
                } else {
                  throw new IllegalArgumentException(s"Cannot determine if key is fact or dim for ordering : $ordering")
                }
              }
          }

          val hasFactSortBy = allFactSortBy.nonEmpty
          val isFactDriven: Boolean = {
            val primaryCheck: Boolean =
            (isAsyncFactDrivenQuery
              || isSyncFactDrivenQuery
              || (!request.forceDimensionDriven &&
              ((hasFactFilters && checkIfBestCandidatesHasAllFactFiltersInDim(allFactFilters.toSet, bestCandidatesOption))
                || hasFactSortBy)))

            val secondaryCheck: Boolean =
              !request.forceDimensionDriven && allDimSortBy.isEmpty && allRequestedDimensionPrimaryKeyAliases.isEmpty && allNonFactFilterAliases.isEmpty
            primaryCheck || secondaryCheck
          }

          //if we are dim driven, add primary key of highest level dim
          if(dimDrivenRequestedDimensionPrimaryKeyAliases.nonEmpty && !isFactDriven) {
            val dimDrivenHighestLevelDim =
              dimDrivenRequestedDimensionPrimaryKeyAliases
                .map(pk => registry.getDimensionByPrimaryKeyAlias(pk, Option.apply(publicFact.dimRevision)).get) //we can do .get since we already checked above
                .to[SortedSet]
                .lastKey

            val addDim = {
              if(allRequestedDimensionPrimaryKeyAliases.nonEmpty) {
                val requestedDims = allRequestedDimensionPrimaryKeyAliases
                  .map(pk => registry.getDimensionByPrimaryKeyAlias(pk, Option.apply(publicFact.dimRevision)).get)
                  .to[SortedSet]
                val allRequestedPKAlreadyExist =
                  dimDrivenRequestedDimensionPrimaryKeyAliases.forall(pk => requestedDims.exists(_.columnsByAlias(pk)))
                !allRequestedPKAlreadyExist
              } else {
                true
              }
            }
            if(addDim) {
              allRequestedDimensionPrimaryKeyAliases += dimDrivenHighestLevelDim.primaryKeyByAlias
              dimDrivenRequestedDimensionPrimaryKeyAliases.foreach { pk =>
                if(dimDrivenHighestLevelDim.columnsByAlias(pk) || dimDrivenHighestLevelDim.primaryKeyByAlias == pk) {
                  //do nothing, we've got this pk covered
                } else {
                  //uncovered pk, we need to do join
                  allRequestedDimensionPrimaryKeyAliases += pk
                }
              }
            }
          }
          val finalAllRequestedDimensionPrimaryKeyAliases = allRequestedDimensionPrimaryKeyAliases.toSet

          val (dimensionCandidates: SortedSet[DimensionCandidate]
          , pushDownFilterMapResult: mutable.HashMap[String, PushDownFilter]
          , allRequestedDimAliases: mutable.TreeSet[String])  =
            buildDimensionCandidateSet(registry
              , publicFact
              , finalAllRequestedDimensionPrimaryKeyAliases
              , allNonFactFilterAliases
              , filterMap
              , allRequestedNonFactAliases
              , allRequestedFactAliases
              , filterPostProcess
              , allDimSortBy
              , isFactDriven
              , pushDownFilterMap
              , allProjectedAliases)

          pushDownFilterMap ++= pushDownFilterMapResult

          /*UNUSED Feature
          //if we are dim driven, and we have no ordering, and we only have a single primary key alias in request fields
          //add default ordering by that primary key alias
          if(!isFactDriven && allDimSortBy.isEmpty && request.ordering.isEmpty) {
            val primaryKeyAliasesRequested = allRequestedDimensionPrimaryKeyAliases.filter(allRequestedAliases.apply)
            if(primaryKeyAliasesRequested.size == 1) {
              allDimSortBy.put(primaryKeyAliasesRequested.head, ASC)
            }
          }
          */

          //we don't count fk filters here
          val hasNonFKDimFilters = allNonFactFilterAliases.filterNot(filterPostProcess(_)).nonEmpty
          val hasDimFilters = allNonFactFilterAliases.nonEmpty
          val hasDimSortBy = allDimSortBy.nonEmpty
          val hasNonDrivingDimSortOrFilter = dimensionCandidates.exists(dc => !dc.isDrivingDimension && (dc.hasNonFKOrForcedFilters || dc.hasNonFKSortBy))
          val hasDrivingDimNonFKNonPKSortBy = dimensionCandidates.filter(dim => dim.isDrivingDimension && dim.hasNonFKNonPKSortBy).nonEmpty

          val hasNonDrivingDimNonFKNonPKFilter = dimensionCandidates.filter(dim => !dim.isDrivingDimension && dim.hasNonFKOrForcedFilters).nonEmpty

          val anyDimHasNonFKNonForceFilter = dimensionCandidates.exists(dim=> dim.hasNonFKNonForceFilters)

          //dimensionCandidates.filter(dim=> dim.isDrivingDimension && !dim.filters.intersect(filterMap.values.toSet).isEmpty


          val finalAllRequestedCols = {
            request.selectFields.map {
              case fd if allRequestedFactAliases(fd.field) &&
                ((isFactDriven && request.requestType == AsyncRequest)
                  || (!allRequestedNonFactAliases(fd.field) && !dimDrivenRequestedDimensionPrimaryKeyAliases(fd.field))) =>
                FactColumnInfo(fd.field)
              case fd if constantValueFields(fd.field) =>
                ConstantColumnInfo(fd.alias.getOrElse(fd.field), fd.value.get)
              case fd if allRequestedDimAliases(fd.field)=>
                DimColumnInfo(fd.field)
              case fd =>
                FactColumnInfo(fd.field)
            }
          }

          val isRequestingDistict = {
            val distinctValue =  request.additionalParameters.get(Distinct)
            if(distinctValue.isDefined) {
              if(distinctValue.get == DistinctValue(true)) {
                true
              } else false
            } else false
          }

          val finalAllSortByCols = {
            request.sortBy.map {
              case od if allFactSortBy.contains(od.field) =>
                FactSortByColumnInfo(od.field, od.order)
              case od if allDimSortBy.contains(od.field) =>
                DimSortByColumnInfo(od.field, od.order)
              case od =>
                throw new IllegalStateException(s"Failed to identify source for ordering col : $od")
            }
          }


          val includeRowCount = request.includeRowCount || (request.requestType == SyncRequest && request.paginationStartIndex < 0)

          val hasLowCardinalityDimFilters = dimensionCandidates.exists(_.hasLowCardinalityFilter)

          val dimensionRelations: DimensionRelations = {
            val publicDimsInRequest = dimensionCandidates.map(_.dim.name)
            var relations: Map[(String, String), Boolean] = Map.empty
            val seq = dimensionCandidates.toIndexedSeq

            var i = 0
            while(i < seq.size) {
              var j = i + 1
              while(j < seq.size) {
                val a = seq(i)
                val b = seq(j)
                val nonDirectRelations = registry.findDimensionPath(a.dim, b.dim)
                val allIndirectRelationsInRequest = nonDirectRelations.forall(pd => publicDimsInRequest(pd.name))
                val related = (a.dim.foreignKeySources.contains(b.dim.name)
                  || b.dim.foreignKeySources.contains(a.dim.name)
                  || (nonDirectRelations.nonEmpty && allIndirectRelationsInRequest)
                )
                relations += ((a.dim.name, b.dim.name) -> related)
                j+=1
              }
              i+=1
            }
            DimensionRelations(relations)
          }

          new RequestModel(request.cube, bestCandidatesOption, allFactFilters.to[SortedSet], dimensionCandidates,
            finalAllRequestedCols, finalAllSortByCols, allRequestedNonFactAliases.toSet,
            registry.getDimCardinalityEstimate(dimensionCandidates, request, entityPublicDimSet.toSet, filterMap,isDebugEnabled),
            bestCandidatesOption.map(
              _.facts.values
                .map(f => (f.fact.name, f.fact.engine) -> registry.getFactRowsCostEstimate(dimensionCandidates,f, request, entityPublicDimSet.toSet, filterMap, isDebugEnabled)).toMap
            ).getOrElse(Map.empty),
            factSortByMap = allFactSortBy.toMap,
            dimSortByMap = allDimSortBy.toMap,
            isFactDriven = isFactDriven,
            hasFactFilters = hasFactFilters,
            hasMetricFilters = hasMetricFilters,
            hasNonFKFactFilters = hasNonFKFactFilters,
            hasFactSortBy = hasFactSortBy,
            hasDimFilters = hasDimFilters,
            hasNonFKDimFilters = hasNonFKDimFilters,
            hasDimSortBy = hasDimSortBy,
            forceDimDriven = request.forceDimensionDriven,
            forceFactDriven = request.forceFactDriven,
            hasNonDrivingDimSortOrFilter = hasNonDrivingDimSortOrFilter,
            hasDrivingDimNonFKNonPKSortBy = hasDrivingDimNonFKNonPKSortBy,
            hasNonDrivingDimNonFKNonPKFilter =  hasNonDrivingDimNonFKNonPKFilter,
            anyDimHasNonFKNonForceFilter = anyDimHasNonFKNonForceFilter,
            schema = request.schema,
            requestType = request.requestType,
            localTimeDayFilter = localTimeDayFilter,
            localTimeHourFilter = localTimeHourFilter,
            localTimeMinuteFilter = localTimeMinuteFilter,
            utcTimeDayFilter = utcTimeDayFilter,
            utcTimeHourFilter = utcTimeHourFilter,
            utcTimeMinuteFilter = utcTimeMinuteFilter,
            startIndex = request.paginationStartIndex,
            maxRows = request.rowsPerPage,
            includeRowCount = includeRowCount,
            isDebugEnabled = isDebugEnabled,
            additionalParameters = request.additionalParameters,
            factSchemaRequiredAliasesMap = factSchemaRequiredAliasesMap.toMap,
            reportingRequest = request,
            queryGrain = queryGrain,
            isRequestingDistict = isRequestingDistict,
            hasLowCardinalityDimFilters = hasLowCardinalityDimFilters,
            requestedDaysLookBack = requestedDaysLookBack,
            requestedDaysWindow = requestedDaysWindow,
            outerFilters = allOuterFilters,
            requestedFkAliasToPublicDimensionMap = allRequestedFkAliasToPublicDimMap,
            orFilterMeta = allOrFilterMeta.toSet,
            dimensionRelations = dimensionRelations
            )
      }
    }
  }

  def validateMaxLookBackWindow(localTimeDayFilter:Filter, factName:String, maxDaysWindow:Int, maxDaysLookBack:Int): (Int, Int) = {
    localTimeDayFilter match {
      case BetweenFilter(_,from,to) =>
        val requestedDaysWindow = DailyGrain.getDaysBetween(from, to)
        val requestedDaysLookBack = DailyGrain.getDaysFromNow(from)
        require(requestedDaysWindow <= maxDaysWindow,
          MaxWindowExceededError(maxDaysWindow, DailyGrain.getDaysBetween(from, to), factName))
        require(requestedDaysLookBack <= maxDaysLookBack,
          MaxLookBackExceededError(maxDaysLookBack, DailyGrain.getDaysFromNow(from), factName))
        (requestedDaysWindow, requestedDaysLookBack)

      case InFilter(_,dates, _, _) =>
        val requestedDaysWindow = dates.size
        require(dates.size < maxDaysWindow,
          MaxWindowExceededError(maxDaysWindow, dates.size, factName))
        var maxDiff: Integer = scala.Int.MinValue
        dates.foreach {
          date => {
            val curDiff = DailyGrain.getDaysFromNow(date)
            if(curDiff > maxDiff) maxDiff = curDiff
            require(curDiff <= maxDaysLookBack,
              MaxLookBackExceededError(maxDaysLookBack, curDiff, factName))
          }
        }
        (requestedDaysWindow, maxDiff)

      case EqualityFilter(_,date, _, _) =>
        val requestedDaysLookBack = DailyGrain.getDaysFromNow(date)
        require(requestedDaysLookBack <= maxDaysLookBack,
          MaxLookBackExceededError(maxDaysLookBack, requestedDaysLookBack, factName))
        (1, requestedDaysLookBack)
      case a =>
        throw new IllegalArgumentException(s"Filter operation not supported. Day filter can be between, in, equality filter : $a")
    }
  }

  /*
   *  FilterOperation Helper functions:
   *  Designed to normalize interaction with FilterOps so that
   *  all filter types can expose the same behavior
   *  specific to RequestModel interaction.
   */

  /**
    * Given a filter and table,
    * 1. Find the filter's primary data type.
    * 2. Validate the length of that filter
    *    against max allowable string length.
    * LIMITATIONS/TODO: Currently, only checks string length
    * and currently only against basic, non-combinable filter types.
    * @param publicTable - Either a PublicFact or PublicDimension
    * @param filter - Filter to check.
    * @return - True or False, with the expected length.
    */
  def validateLengthForFilterValue(publicTable: PublicTable, filter: Filter): (Boolean, Int) = {
    /*
    passed in filters are guaranteed to never be multi-field, or multi-filter filters, as this behavior is
    picked out before this function is called.
     */
    val dataType = {
      publicTable match {
        case publicDim: PublicDimension => publicDim.nameToDataTypeMap(publicDim.columnsByAliasMap(filter.field).name)
        case publicFact: PublicFact => publicFact.dataTypeForAlias(publicFact.columnsByAliasMap(filter.field).alias)
        case _ => None
      }
    }

    def validateLength(values : List[String], maxLength:Int) : (Boolean, Int) = {
      val expectedLength = if (maxLength == 0) MAX_ALLOWED_STR_LEN else maxLength
      if (values.forall(_.length <= expectedLength))
        (true, expectedLength)
      else
        (false, expectedLength)
    }

    dataType match {
      case None => throw new IllegalArgumentException(s"Unable to find expected PublicTable as PublicFact or PublicDimension.")
      case StrType(length, _, _) => filter match {
        case InFilter(_, values, _, _) => validateLength(values, length)
        case NotInFilter(_, values, _, _) => validateLength(values, length)
        case EqualityFilter(_, value, _, _) => validateLength(List(value), length)
        case FieldEqualityFilter(_, value, _, _) => validateLength(List(value), length)
        case NotEqualToFilter(_, value, _, _) => validateLength(List(value), length)
        case LikeFilter(_, value, _, _) => validateLength(List(value), length)
        case BetweenFilter(_, from, to) => validateLength(List(from, to), length)
        case IsNullFilter(_, _, _) | IsNotNullFilter(_, _, _) | PushDownFilter(_) | OuterFilter(_) | OrFilter(_) => (true, MAX_ALLOWED_STR_LEN)
        case _ => throw new Exception(s"Unhandled FilterOperation $filter.")
      }
      case _ => (true, MAX_ALLOWED_STR_LEN)
    }
  }

  /**
    * Validate all passed in fact filters are Aliases
    * for Primary Keys.
    * Used to validate if a query is Dim Driven.
    * @param allFactFilters - filters to check
    * @param registry - Registry to check against.
    * @return - True only if all fact filters are PK Aliases.
    */
  private def checkAllFactFiltersArePKAliases(allFactFilters: Set[Filter]
                                     , registry: Registry) : Boolean = {
    val allFields: Set[String] = returnFieldSetOnMultipleFiltersWithoutValidation(allFactFilters)
    allFields forall(field => registry isPrimaryKeyAlias field)
  }

  /**
    * Create a map from filter field(s) to FilterOperation.
    * @param allFilters - filters to convert.
    * @return - Map from filter Field to FilterOperation.
    */
  def createAllFilterMap(allFilters: Set[Filter]) : Map[String, FilterOperation] = {
    allFilters.map{
      filter => returnFieldAndOperationMapWithoutValidation(filter) }.flatten.toMap
  }

  /**
    * Check if any filters are non-foreignKeys.
    * @param allFactFilters - filters to check.
    * @param filterPostProcess - check if post processed fields on current filters is nonempty.
    * @return - True if any fact filter is non-FK.
    */
  private def hasNonFKFactFiltersChecker(allFactFilters: Set[Filter]
                         , filterPostProcess: Set[String]) : Boolean = {
    val fieldSet: Set[String] = returnFieldSetOnMultipleFiltersWithoutValidation(allFactFilters)
    fieldSet.filterNot(field => filterPostProcess(field)).nonEmpty
  }

  /**
    * Check if the query has any metric filters.
    * @param allFactFilters - all filters to check.
    * @param publicFact - Fact ot check.
    * @return - True if at least one fact-filter is a PublicFactColumn.
    */
  private def checkIfHasMetricFilters(allFactFilters: Set[Filter]
                             , publicFact: PublicFact): Boolean = {
    val fieldSet: Set[String] = returnFieldSetOnMultipleFiltersWithoutValidation(allFactFilters)

    fieldSet.exists { field =>
      publicFact.columnsByAliasMap.contains(field) && publicFact.columnsByAliasMap(field).isInstanceOf[PublicFactColumn]
    }
  }

  /**
    * Verify if the dim holds all fact filters.
    * used for dim, fact-driven query checking.
    * @param allFactFilters - all filters to check.
    * @param bestCandidatesOption - current best candidate to check.
    * @return - false if any filter is outside of the dim.
    */
  private def checkIfBestCandidatesHasAllFactFiltersInDim(allFactFilters: Set[Filter]
                                                 , bestCandidatesOption: Option[BestCandidates]) : Boolean = {
    val fieldSet: Set[String] = returnFieldSetOnMultipleFiltersWithoutValidation(allFactFilters)
    !fieldSet.forall(field => bestCandidatesOption.get.dimColAliases(field))
  }

  /**
    * Check of the dimension has low cardinality filters.
    * @param injectFilters - injected Filters
    * @param injectDim - Injected Dimension
    * @param colAliases - Known column Aliases.
    * @param publicFact - Fact to use.
    * @param publicDim - Primary dim to check.
    * @return - true if any low cardinality filters exist.
    */
  private def checkIfHasLowCardinalityFilters(injectFilters: SortedSet[Filter]
                                      , injectDim: PublicDimension
                                      , colAliases: Set[String]
                                      , publicFact: PublicFact
                                      , publicDim: PublicDimension): Boolean = {
    injectFilters.view.filter(!_.isPushDown).exists {
      filter =>
        val fields = returnFieldSetWithoutValidation(filter)
        fields.exists( field =>
          (colAliases(field) || injectDim.columnsByAlias(field)) &&
            !publicFact.columnsByAlias(field) &&
            !(publicDim.containsHighCardinalityFilter(filter) || injectDim.containsHighCardinalityFilter(filter)))
    }

  }

  // populate all forced filters from dim
  /**
    * Populate filterAliases and filterMap from dim.
    * @param publicDimension - dim to populate from.
    * @param allNonFactFilterAliases - all known filter aliases.
    * @param filterPostProcess - map of filters to use.
    * @return - both allFilterAliases and filterMap.
    */
  private def populateAllForcedFiltersForDim(publicDimension: PublicDimension
                                     , allNonFactFilterAliases: Set[String]
                                     , filterPostProcess: Set[String]) : (Set[Filter], Boolean) = {
    val returnedFilters: mutable.Set[Filter] = mutable.Set.empty
    val hasForcedFilters = publicDimension.forcedFilters.foldLeft(false) {
      (b, filter) =>
        val fields = returnFieldSetWithoutValidation(filter)
        val fieldsResults: Set[Boolean] = fields map {
          field =>
            val result =
              if(!allNonFactFilterAliases(field) && !filterPostProcess(field)) {
                returnedFilters += filter
                true
              } else false
            b || result
        }
        fieldsResults.contains(true)
    }

    (returnedFilters.toSet, hasForcedFilters)
  }

  // populate all forced filters from fact
  /**
    * Populate filterAliases and filterMap from forced filters.
    * @param publicFact - fact to populate from.
    * @param allFilterAliases - all known filter aliases.
    * @param filterMap - Map of filters to use.
    * @return - both allFilterAliases and filterMap.
    */
  private def populateFiltersFromFactForcedFilters(publicFact: PublicFact
                                           , allFilterAliases: Set[String]
                                           , filterMap: Map[String, Filter]) : (Set[String], Map[String, Filter]) = {
    var returnedFilterAliases : mutable.Set[String] =  mutable.Set.empty ++ allFilterAliases
    val returnedFilterMap : mutable.HashMap[String, Filter] = mutable.HashMap(filterMap.toSeq:_*)
    publicFact.forcedFilters.foreach {
      filter =>
        val fields = returnFieldSetWithoutValidation(filter)
        fields.foreach {
          field =>
            if(!returnedFilterAliases(field)) {
              returnedFilterAliases += field
              returnedFilterMap.put(field, filter)
            }
        }
    }
    (returnedFilterAliases.toSet, returnedFilterMap.toMap)
  }

  /**
    * Validate Fields in FultiField filter safely.
    * @param publicTable - table to validate against.
    * @param filter - filter to validate.
    */
  private def validateFieldsInMultiFieldForcedFilter(publicTable: PublicTable
                                             , filter: MultiFieldForcedFilter) : Unit = {
    require(publicTable.columnsByAliasMap.contains(filter.field) && publicTable.columnsByAliasMap.contains(filter.compareTo)
      , IncomparableColumnError(filter.field, filter.compareTo)
    )
    publicTable match {
      case publicDim: PublicDimension =>
        val firstDataType: DataType = publicDim.nameToDataTypeMap(publicDim.columnsByAliasMap(filter.field).name)
        val compareToDataType: DataType = publicDim.nameToDataTypeMap(publicDim.columnsByAliasMap(filter.compareTo).name)
        require(firstDataType.jsonDataType == compareToDataType.jsonDataType, "Both fields being compared must be the same Data Type.")
      case publicFact: PublicFact =>
        val firstDataType: DataType = publicFact.dataTypeForAlias(publicFact.columnsByAliasMap(filter.field).alias)
        val compareToDataType: DataType = publicFact.dataTypeForAlias(publicFact.columnsByAliasMap(filter.compareTo).alias)
        require(firstDataType.jsonDataType == compareToDataType.jsonDataType, "Both fields being compared must be the same Data Type.")
      case _ => None
    }
  }

  /**
    * Given a filter of unknown type, classify it and pass it through
    * necessary requirements, then determine if one or more aliases
    * should be returned.
    * @param filter - Filter to check & populate the map based on.
    * @param allRequestedAliases
    * @param publicFact
    * @return - A set of filter Aliases, or an error.
    */
  private def validateAndReturnFilterData(filter: Filter
                                  , allRequestedAliases: Set[String]
                                  , publicFact: PublicFact) : (Map[String, Filter], Set[String], Set[Filter], Set[OrFilterMeta]) = {
    val filterResultBag: (Map[String, Filter], Set[String], Set[Filter], Set[OrFilterMeta]) = filter match {
      //all filters passed in OuterFilter must be added to the Filter Alias Set.
      case outerFilter: OuterFilter =>
        (staticMappedFilterRender(outerFilter, publicFact), Set.empty, validateOuterFilterRequirementsAndReturn(outerFilter, allRequestedAliases), Set.empty)
      //Both filters in the Field Equality filter must be added to the Filter Alias Set.
      case fieldEqualityFilter: FieldEqualityFilter =>
        (staticMappedFilterRender(fieldEqualityFilter, publicFact)
          , Set(fieldEqualityFilter.field, fieldEqualityFilter.compareTo), Set.empty, Set.empty)
      //Filters passed through an Or Filter do not get added to the Set.
      case orFilter: OrFilter =>
        (staticMappedFilterRender(orFilter, publicFact), Set.empty, Set.empty, validateOrFilterRequirementsAndReturn(orFilter, publicFact))
      //Between filter gets its field added to the Set.
      case betweenFilter: BetweenFilter =>
        (staticMappedFilterRender(betweenFilter, publicFact), Set(betweenFilter.field), Set.empty, Set.empty)
      //Equality filter gets its field added to the Set.
      case equalityFilter: EqualityFilter =>
        (staticMappedFilterRender(equalityFilter, publicFact), Set(equalityFilter.field), Set.empty, Set.empty)
      //In filter gets its field added to the Set.
      case inFilter: InFilter =>
        (staticMappedFilterRender(inFilter, publicFact), Set(inFilter.field), Set.empty, Set.empty)
      //Not-In filter gets its field added to the Set.
      case notInFilter: NotInFilter =>
        (staticMappedFilterRender(notInFilter, publicFact), Set(notInFilter.field), Set.empty, Set.empty)
      case greaterThanFilter: GreaterThanFilter =>
        (staticMappedFilterRender(greaterThanFilter, publicFact), Set(greaterThanFilter.field), Set.empty, Set.empty)
      case lessThanFilter: LessThanFilter =>
        (staticMappedFilterRender(lessThanFilter, publicFact), Set(lessThanFilter.field), Set.empty, Set.empty)
      case likeFilter: LikeFilter =>
        (staticMappedFilterRender(likeFilter, publicFact), Set(likeFilter.field), Set.empty, Set.empty)
      case isNullFilter: IsNullFilter =>
        (staticMappedFilterRender(isNullFilter, publicFact), Set(isNullFilter.field), Set.empty, Set.empty)
      case notEqualToFilter: NotEqualToFilter =>
        (staticMappedFilterRender(notEqualToFilter, publicFact), Set(notEqualToFilter.field), Set.empty, Set.empty)
      case _ : Any => throw new IllegalArgumentException("Input filter is not a valid filter to check!  Found " + filter.toString)
    }
    filterResultBag
  }

  /**
    * For returning all filter fields relevant to the current filter type.
    * Used for pre-validated filters, such as those coming
    * from the PublicFact (forced filters).
    * @param filter - Filter to return data from.
    * @return - Set dependent upon input filter type only.
    */
  def returnFieldSetWithoutValidation(filter: Filter) : Set[String] = {
    filter match {
      case _: OuterFilter => Set.empty
      case fieldEqualityFilter: FieldEqualityFilter => Set(fieldEqualityFilter.field, fieldEqualityFilter.compareTo)
      case _: OrFilter => Set.empty
      case betweenFilter: BetweenFilter => Set(betweenFilter.field)
      case equalityFilter: EqualityFilter => Set(equalityFilter.field)
      case inFilter: InFilter => Set(inFilter.field)
      case notInFilter: NotInFilter => Set(notInFilter.field)
      case notEqualToFilter: NotEqualToFilter => Set(notEqualToFilter.field)
      case greaterThanFilter: GreaterThanFilter => Set(greaterThanFilter.field)
      case lessThanFilter: LessThanFilter => Set(lessThanFilter.field)
      case isNotNullFilter: IsNotNullFilter => Set(isNotNullFilter.field)
      case likeFilter: LikeFilter => Set(likeFilter.field)
      case notEqualToFilter: NotEqualToFilter => Set(notEqualToFilter.field)
      case isNullFilter: IsNullFilter => Set(isNullFilter.field)
      case pushDownFilter: PushDownFilter => returnFieldSetWithoutValidation(pushDownFilter.f)
      case t: Filter => throw new IllegalArgumentException(t.field + " with filter " + t.toString)
    }
  }

  /**
    * Given an input filter, return a map of its field(s) to its filter operation.
    * @param filter - filter to return.
    * @return - Map of filter fields to FilterOperation.
    */
  def returnFieldAndOperationMapWithoutValidation(filter: Filter) : Map[String, FilterOperation] = {
    filter match {
      case _: OuterFilter => Map.empty
      case fieldEqualityFilter: FieldEqualityFilter => Map(fieldEqualityFilter.field -> fieldEqualityFilter.operator, fieldEqualityFilter.compareTo -> fieldEqualityFilter.operator)
      case _: OrFilter => Map.empty
      case betweenFilter: BetweenFilter => Map(betweenFilter.field -> betweenFilter.operator)
      case equalityFilter: EqualityFilter => Map(equalityFilter.field -> equalityFilter.operator)
      case inFilter: InFilter => Map(inFilter.field -> inFilter.operator)
      case notInFilter: NotInFilter => Map(notInFilter.field -> notInFilter.operator)
      case notEqualToFilter: NotEqualToFilter => Map(notEqualToFilter.field -> notEqualToFilter.operator)
      case greaterThanFilter: GreaterThanFilter => Map(greaterThanFilter.field -> greaterThanFilter.operator)
      case lessThanFilter: LessThanFilter => Map(lessThanFilter.field -> lessThanFilter.operator)
      case isNotNullFilter: IsNotNullFilter => Map(isNotNullFilter.field -> isNotNullFilter.operator)
      case likeFilter: LikeFilter => Map(likeFilter.field -> likeFilter.operator)
      case notEqualToFilter: NotEqualToFilter => Map(notEqualToFilter.field -> notEqualToFilter.operator)
      case isNullFilter: IsNullFilter => Map(isNullFilter.field -> isNullFilter.operator)
      case pushDownFilter: PushDownFilter => returnFieldAndOperationMapWithoutValidation(pushDownFilter.f)
      case t: Filter => throw new IllegalArgumentException(t.field + " with filter " + t.toString)
    }
  }

  /**
    * Given a list of filters, return all given fields.
    * @param allFilters - filters to render.
    * @return - Set of fields associated with the given filters.
    */
  def returnFieldSetOnMultipleFiltersWithoutValidation(allFilters: Set[Filter]): Set[String] = {
    allFilters.map(filter => returnFieldSetWithoutValidation(filter)).flatten
  }

  /**
    * Render a between filter with a statically mapped value.
    * @param filter - filter to render.
    * @param reverseMapping - Static mapping for the field.
    * @return - Statically mapped Between Filter.
    */
  private def renderStaticMappedBetweenFilter(filter: BetweenFilter
                                     , reverseMapping: Map[String, Set[String]]) : BetweenFilter = {
    val from = filter.from
    val to = filter.to
    val field = filter.field
    require(reverseMapping.contains(from), s"Unknown filter from value for field=$field, from=$from")
    require(reverseMapping.contains(to), s"Unknown filter to value for field=$field, to=$to")
    val fromSet = reverseMapping(from)
    val toSet = reverseMapping(to)
    require(fromSet.size == 1 && toSet.size == 1,
      s"Cannot perform between filter, the column has static mapping which maps to multiple values, from=$from maps to fromSet=$fromSet, to=$to maps to toSet=$toSet"
    )
    BetweenFilter(field, fromSet.head, toSet.head)
  }

  /**
    * Render a equality filter with a statically mapped value.
    * * @param filter - filter to render.
    * * @param reverseMapping - Static mapping for the field.
    * * @return - Statically mapped Equality Filter.
    */
  private def renderStaticMappedEqualityFilter(filter: EqualityFilter
                                      , reverseMapping: Map[String, Set[String]]) : Filter = {
    val field = filter.field
    val value = filter.value
    require(reverseMapping.contains(value), s"Unknown filter value for field=$field, value=$value")
    val valueSet = reverseMapping(value)
    if(valueSet.size > 1) {
      InFilter(field, valueSet.toList)
    } else {
      EqualityFilter(field, valueSet.head)
    }
  }

  /**
    * Render an in filter with a statically mapped value.
    * * @param filter - filter to render.
    * * @param reverseMapping - Static mapping for the field.
    * * @return - Statically mapped In Filter.
    */
  private def renderStaticMappedInFilter(filter: InFilter
                                 , reverseMapping: Map[String, Set[String]]) : InFilter = {
    val field = filter.field
    val values = filter.values
    val mapped = values.map {
      value =>
        require(reverseMapping.contains(value), s"Unknown filter value for field=$field, value=$value")
        reverseMapping(value)
    }
    InFilter(field, mapped.flatten)
  }

  /**
    * Render a NotIn filter with a statically mapped value.
    * * @param filter - filter to render.
    * * @param reverseMapping - Static mapping for the field.
    * * @return - Statically mapped NotIn Filter.
    */
  private def renderStaticMappedNotInFilter(filter: NotInFilter
                                   , reverseMapping: Map[String, Set[String]]): NotInFilter = {
    val field = filter.field
    val values = filter.values
    val mapped = values.map {
      value =>
        require(reverseMapping.contains(value), s"Unknown filter value for field=$field, value=$value")
        reverseMapping(value)
    }
    NotInFilter(field, mapped.flatten)
  }

  /**
    * As Or Filter does not get an explicit check for static mapping, its internal filters
    * still get checked.
    * @param filter - OrFilter to check
    * @param publicFact - fact used for reverse mapping.
    * @return - error, if applicable.
    */
  private def renderStaticMappedOrFilter(filter: OrFilter
                                , publicFact: PublicFact): Unit = {
    val filters = filter.filters
    filters map {
      //Validate the input filters are mappable, but do not add to the mapped filters.
      singleFilter =>
        staticMappedFilterRender(singleFilter, publicFact)
    }
  }

  /**
    * 1. Check if the current filter type gets rendered or only validated.
    * 2. Pick up all valid fields from the current filter.
    * 3. Check if each is statically mapped.  If not, yield a tuple for filterMap.
    * 4. If it is statically mapped, apply static mapping logic.
    * @param filter - filter to check
    * @param publicFact - fact used for static mapping.
    * @return - map of statically mapped filters.
    */
  private def staticMappedFilterRender(filter: Filter
                               , publicFact: PublicFact) : Map[String, Filter] = {
    /**
      * For filters with no fields to return, only validate the internal filters.
      */
    filter match {
      case orFilter: OrFilter =>
        renderStaticMappedOrFilter(orFilter, publicFact)
        return Map.empty
      case _ =>
    }

    val validFieldSet : Set[String] = returnFieldSetWithoutValidation(filter)
    var (returnedFilterSet, newMap) :
      (mutable.Set[(String, Filter)], mutable.Set[(String, Map[String, Set[String]])]) = (mutable.Set.empty, mutable.Set.empty)
      validFieldSet.map {
        field: String =>
        if(publicFact.aliasToReverseStaticMapping.contains(field)) newMap ++= Set((field, publicFact.aliasToReverseStaticMapping(field)))
        else returnedFilterSet ++= Set((field, filter))
        }

    /**
      * For filters with returnable, statically mapped fields, return both the filter
      * and its filterValue.
      */
    val setOfFilterFieldsWithReverseMappedFilters = newMap map {
      tuple : (String, Map[String, Set[String]]) =>
        val filterValue = tuple._1
        val reverseMapping = tuple._2
        val reverseMappedFilter: Filter = filter match {
          case f: BetweenFilter =>
            renderStaticMappedBetweenFilter(f, reverseMapping)
          case f: EqualityFilter =>
            renderStaticMappedEqualityFilter(f, reverseMapping)
          case f: InFilter =>
            renderStaticMappedInFilter(f, reverseMapping)
          case f: NotInFilter =>
            renderStaticMappedNotInFilter(f, reverseMapping)
          case f =>
            throw new IllegalArgumentException(s"Unsupported filter operation on statically mapped field : $f")
        }
        (filterValue, reverseMappedFilter)
    }

    (setOfFilterFieldsWithReverseMappedFilters ++ returnedFilterSet).toMap
  }

  //validate for publicFact, publicDim contents.
  /**
    * Validate all passed in filters against a given table (fact or dim)
    * @param allFilters - all filters to checl
    * @param publicTable - table to use
    */
  private def validateAllTabularFilters(allFilters: Set[Filter]
                                , publicTable: PublicTable) : Unit = {
    allFilters foreach {
      filter =>
        val fieldSet = returnFieldSetWithoutValidation(filter)
        fieldSet foreach {
          field =>
            if(publicTable.columnsByAliasMap.contains(field)) {
              val pubCol = publicTable.columnsByAliasMap(field)
              require(publicTable.columnsByAliasMap.contains(field) && pubCol.filters.contains(filter.operator),
                s"Unsupported filter operation : cube=${publicTable.name}, col=$field, operation=${filter.operator}")
            }
        }
        filter match {
          case forcedFilter: MultiFieldForcedFilter =>
            validateFieldsInMultiFieldForcedFilter(publicTable, forcedFilter)
          case orFilter: OrFilter =>
            validateAllTabularFilters(orFilter.filters.toSet, publicTable)
          case _ =>
            val (isValidFilter, length) = validateLengthForFilterValue(publicTable, filter)
            require(isValidFilter, s"Value for ${filter.field} exceeds max length of $length characters.")
        }
    }
  }

  /**
    * Given a filter, check its type against its type requirements.
    * @param filter - Filter to Validate
    * @param allRequestedAliases - all aliases to check against.
    * @return - set of outer Filters.
    */
  private def validateOuterFilterRequirementsAndReturn(filter: OuterFilter
                                               , allRequestedAliases: Set[String]) : Set[Filter] = {
    val outerFilters = filter.filters ++ mutable.TreeSet[Filter]()
    outerFilters.foreach( of => require(allRequestedAliases.contains(of.field), s"OuterFilter ${of.field} is not in selected column list"))
    outerFilters.toSet
  }

  /**
    * Given an OrFilter, find its inner filters and return OrFilterMeta
    * @param filter - filter to traverse.
    * @param publicTable - table to validate against.
    * @return - OrFilterMeta set.
    */
  private def validateOrFilterRequirementsAndReturn(filter: OrFilter
                                            , publicTable: PublicTable) : Set[OrFilterMeta] = {
    val orFilterMap : Map[Boolean, Iterable[Filter]] = filter.filters.groupBy(f => publicTable.columnsByAliasMap.contains(f.field) && publicTable.columnsByAliasMap(f.field).isInstanceOf[PublicFactCol])
    require(orFilterMap.size == 1, s"Or filter cannot have combination of fact and dim filters, factFilters=${orFilterMap.get(true)} dimFilters=${orFilterMap.get(false)}")
    Set(OrFilterMeta(filter, orFilterMap.head._1))
  }

  /**
    * Build a set of Dimension Candidates for the current query.
    * @param registry - Registry to use.
    * @param publicFact - PublicFact to validate against.
    * @param finalAllRequestedDimensionPrimaryKeyAliases - All Dim to PK Aliases.
    * @param allNonFactFilterAliases - All Non-Fact filters.
    * @param filterMap - Map from Alias to Filter.
    * @param allRequestedNonFactAliases - Requested Non-Fact aliases.
    * @param allRequestedFactAliases - Requested Fact Aliases.
    * @param filterPostProcess - Post-Processable filters.
    * @param allDimSortBy - All requested Order By Dims.
    * @param isFactDriven - If this a fact-driven query?
    * @param pushDownFilterMap - Map of push-down filters, gets added-to here.
    * @param allProjectedAliases - All known projected aliases.
    * @return - A set of dimensionCandidates, more found Push Down Filters, and All Requested Dimension Aliases.
    */
  private def buildDimensionCandidateSet(registry: Registry
                                , publicFact: PublicFact
                                , finalAllRequestedDimensionPrimaryKeyAliases: Set[String]
                                , allNonFactFilterAliases: mutable.TreeSet[String]
                                , filterMap: mutable.HashMap[String, Filter]
                                , allRequestedNonFactAliases: mutable.TreeSet[String]
                                , allRequestedFactAliases: mutable.TreeSet[String]
                                , filterPostProcess: mutable.TreeSet[String]
                                , allDimSortBy: mutable.HashMap[String, Order]
                                , isFactDriven: Boolean
                                , pushDownFilterMap: mutable.HashMap[String, PushDownFilter]
                                , allProjectedAliases: Set[String]) : (SortedSet[DimensionCandidate], mutable.HashMap[String, PushDownFilter], mutable.TreeSet[String]) = {
    val finalAllRequestedDimsMap = finalAllRequestedDimensionPrimaryKeyAliases
      .map(pk => pk -> registry.getDimensionByPrimaryKeyAlias(pk, Option.apply(publicFact.dimRevision)).get).toMap
    val allRequestedDimAliases = new mutable.TreeSet[String]()
    var dimOrder : Int = 0
    val dimensionCandidates: SortedSet[DimensionCandidate] = {
      val intermediateCandidates = new mutable.TreeSet[DimensionCandidate]()
      val upperJoinCandidates = new mutable.TreeSet[PublicDimension]()
      finalAllRequestedDimensionPrimaryKeyAliases
        .flatMap(f => registry.getDimensionByPrimaryKeyAlias(f, Option.apply(publicFact.dimRevision)))
        .toIndexedSeq
        .sortWith((a, b) => b.dimLevel < a.dimLevel)
        .foreach {
          publicDimOption =>
            //used to identify the highest level dimension
            dimOrder += 1
            // publicDimOption should always be defined for primary key alias because it is checked above
            val publicDim = publicDimOption
            val colAliases = publicDim.columnsByAlias
            val isDrivingDimension : Boolean = dimOrder == 1

            val filters = new mutable.TreeSet[Filter]()
            //all non foreign key based filters
            val hasNonFKFilters =  allNonFactFilterAliases.foldLeft(false) {
              (b, filter) =>
                val result = if (colAliases.contains(filter) || filter == publicDim.primaryKeyByAlias) {
                  filters += filterMap(filter)
                  true
                } else false
                b || result
            }

            // populate all forced filters from dim
            val (filtersResult, hasForcedFiltersResult) = populateAllForcedFiltersForDim(publicDim, allNonFactFilterAliases.toSet, filterPostProcess.toSet)
            val hasForcedFilters = hasForcedFiltersResult
            filters ++= filtersResult

            val fields = allRequestedNonFactAliases.filter {
              fd =>
                (dimOrder == 1 && colAliases.contains(fd)) ||
                  (colAliases.contains(fd) && !(allRequestedFactAliases(fd) && !allNonFactFilterAliases(fd) && !allDimSortBy.contains(fd)))
            }.toSet

            if(fields.nonEmpty || filters.nonEmpty || !isFactDriven) {
              //push down all key based filters
              filterPostProcess.foreach {
                filter =>
                  if (colAliases(filter) || publicDim.primaryKeyByAlias == filter) {
                    if(pushDownFilterMap.contains(filter)) {
                      filters += pushDownFilterMap(filter)
                    } else {
                      val pushDownFilter = PushDownFilter(filterMap(filter))
                      pushDownFilterMap.put(filter, pushDownFilter)
                      filters += pushDownFilter
                    }
                  }
              }

              //validate filter operation on dim filters
              validateAllTabularFilters(filters.toSet, publicDim)

              val hasNonFKSortBy = allDimSortBy.exists {
                case (sortField, _) =>
                  publicDim.allColumnsByAlias.contains(sortField) && !publicDim.foreignKeyByAlias(sortField)
              }
              val hasNonFKNonPKSortBy = allDimSortBy.exists {
                case (sortField, _) =>
                  publicDim.allColumnsByAlias.contains(sortField) && !publicDim.foreignKeyByAlias(sortField) && !publicDim.primaryKeyByAlias.equals(sortField)
              }

              //keep only one level higher
              val aboveLevel = publicDim.dimLevel + 1
              val prevLevel  = publicDim.dimLevel - 1

              val (foreignkeyAlias: Set[String], lowerJoinCandidates: List[PublicDimension]) = {
                if (finalAllRequestedDimsMap.size > 1) {
                  val foreignkeyAlias = new mutable.TreeSet[String]
                  val lowerJoinCandidates = new mutable.TreeSet[PublicDimension]
                  publicDim.foreignKeyByAlias.foreach {
                    alias =>
                      if (finalAllRequestedDimsMap.contains(alias)) {
                        foreignkeyAlias += alias
                        val pd = finalAllRequestedDimsMap(alias)
                        //only keep lower join candidates
                        if(pd.dimLevel != publicDim.dimLevel && pd.dimLevel <= prevLevel) {
                          lowerJoinCandidates += finalAllRequestedDimsMap(alias)
                        }
                      }
                  }
                  (foreignkeyAlias.toSet, lowerJoinCandidates.toList)
                } else {
                  (Set.empty[String], List.empty[PublicDimension])
                }
              }


              // always include primary key in dimension table for join
              val requestedDimAliases = foreignkeyAlias ++ fields + publicDim.primaryKeyByAlias
              val filteredUpper = upperJoinCandidates.filter(pd => pd.dimLevel != publicDim.dimLevel && pd.dimLevel >= aboveLevel)

              // attempting to find the better upper candidate if exist
              // ads->adgroup->campaign hierarchy, better upper candidate for campaign is ad
              val filteredUpperTopList = {
                val bestUpperCandidates = filteredUpper
                  .filter(pd => pd.foreignKeyByAlias.contains(publicDim.primaryKeyByAlias))
                val bestUpperDerivedCandidate = bestUpperCandidates.find(pd => pd.getBaseDim.isDerivedDimension)
                val bestUpperCandidate = if (bestUpperDerivedCandidate.isDefined) {
                  Set(bestUpperDerivedCandidate.get)
                } else {
                  bestUpperCandidates.take(1)
                }
                if(bestUpperCandidate.isEmpty && upperJoinCandidates.nonEmpty &&
                  ((!publicFact.foreignKeyAliases(publicDim.primaryKeyByAlias) && isFactDriven) || !isFactDriven)) {
                  //inject upper candidates
                  val upper = upperJoinCandidates.last
                  val findDimensionPath = registry.findDimensionPath(publicDim, upper)
                  findDimensionPath.foreach {
                    injectDim =>
                      val injectFilters : SortedSet[Filter] = pushDownFilterMap.collect {
                        case (alias, filter) if injectDim.columnsByAlias.contains(alias) => filter.asInstanceOf[Filter]
                      }.to[SortedSet]
                      val injectFilteredUpper = upperJoinCandidates.filter(pd => pd.dimLevel != injectDim.dimLevel && pd.dimLevel >= aboveLevel)
                      val injectBestUpperCandidate = injectFilteredUpper
                        .filter(pd => pd.foreignKeyByAlias.contains(injectDim.primaryKeyByAlias)).takeRight(1)
                      val hasLowCardinalityFilter = checkIfHasLowCardinalityFilters(injectFilters, injectDim, colAliases, publicFact, publicDim)
                      intermediateCandidates += new DimensionCandidate(
                        injectDim
                        , Set(injectDim.primaryKeyByAlias, publicDim.primaryKeyByAlias)
                        , injectFilters
                        , injectBestUpperCandidate.toList
                        , List(publicDim)
                        , false
                        , hasNonFKFilters || hasForcedFilters
                        , hasNonFKFilters // this does not include force Filters
                        , hasNonFKSortBy
                        , hasNonFKNonPKSortBy
                        , hasLowCardinalityFilter
                        , hasPKRequested = allProjectedAliases.contains(publicDim.primaryKeyByAlias)
                        , hasNonPushDownFilters = injectFilters.exists(filter => !filter.isPushDown)
                      )

                  }
                  val newFilteredUpper = findDimensionPath.filter(pd => pd.dimLevel != publicDim.dimLevel && pd.dimLevel >= aboveLevel)
                  newFilteredUpper.filter(pd => pd.foreignKeyByAlias.contains(publicDim.primaryKeyByAlias)).takeRight(1)
                } else {
                  bestUpperCandidate
                }
              }

              val filteredLowerTopList = lowerJoinCandidates.lastOption.fold(List.empty[PublicDimension])(List(_))

              val hasLowCardinalityFilter =
                checkIfHasLowCardinalityFilters(filters ++ scala.collection.immutable.SortedSet[Filter](), publicDim, colAliases, publicFact, publicDim)

              intermediateCandidates += new DimensionCandidate(
                publicDim
                , foreignkeyAlias ++ fields + publicDim.primaryKeyByAlias
                , filters.to[SortedSet]
                , filteredUpperTopList.toList
                , filteredLowerTopList
                , isDrivingDimension
                , hasNonFKFilters || hasForcedFilters
                , hasNonFKFilters // this does not include force Filters
                , hasNonFKSortBy
                , hasNonFKNonPKSortBy
                , hasLowCardinalityFilter
                , hasPKRequested = allProjectedAliases.contains(publicDim.primaryKeyByAlias)
                , hasNonPushDownFilters = filters.exists(filter => !filter.isPushDown)
              )
              allRequestedDimAliases ++= requestedDimAliases
              // Adding current dimension to uppper dimension candidates
              upperJoinCandidates+=publicDim
            }
        }
      intermediateCandidates.to[SortedSet]
    }
    (dimensionCandidates, pushDownFilterMap, allRequestedDimAliases)
  }
}

case class RequestModelResult(model: RequestModel, dryRunModelTry: Option[Try[RequestModel]])

object RequestModelFactory extends Logging {
  // If no revision is specified, return a Tuple of RequestModels 1-To serve the response 2-Optional dryrun to test new fact revisions
  def fromBucketSelector(request: ReportingRequest, bucketParams: BucketParams, registry: Registry, bucketSelector: BucketSelector, utcTimeProvider: UTCTimeProvider = PassThroughUTCTimeProvider) : Try[RequestModelResult] = {
    val selectedBucketsTry: Try[CubeBucketSelected] = bucketSelector.selectBucketsForCube(request.cube, bucketParams)
    selectedBucketsTry match {
      case Success(buckets: CubeBucketSelected) =>
        for {
          defaultRequestModel <- RequestModel.from(request, registry, utcTimeProvider, Some(buckets.revision))
        } yield {
          val dryRunModel: Option[Try[RequestModel]] = if (buckets.dryRunRevision.isDefined) {
            Option(Try {
              var updatedRequest = request
              if (buckets.dryRunEngine.isDefined) {
                buckets.dryRunEngine.get match {
                  case DruidEngine =>
                    updatedRequest = ReportingRequest.forceDruid(request)
                  case OracleEngine =>
                    updatedRequest = ReportingRequest.forceOracle(request)
                  case HiveEngine =>
                    updatedRequest = ReportingRequest.forceHive(request)
                  case PrestoEngine =>
                    updatedRequest = ReportingRequest.forcePresto(request)
                  case a =>
                    throw new IllegalArgumentException(s"Unknown engine: $a")
                }
              }
              RequestModel.from(updatedRequest, registry, utcTimeProvider, buckets.dryRunRevision)
            }.flatten)
          } else None
          RequestModelResult(defaultRequestModel, dryRunModel)
        }

      case Failure(t) =>
        warn("Failed to compute bucketing info, will use default revision to return response", t)
        RequestModel.from(request, registry, utcTimeProvider).map(model => RequestModelResult(model, None))
    }
  }
}
