import org.apache.spark.sql.magellan.dsl.expressions._
import org.apache.spark.sql.{SparkSession, DataFrame, SaveMode}
import org.apache.spark.sql.functions.{udf, lit, col, concat_ws, collect_list, expr, size, split}
import org.rogach.scallop._

import magellan.Point

object JobGeoMapping {

  val LABELS_SEPARATOR              = ","
  val MAGELLAN_INDEX_SEPARATOR      = ","
  val METADATA_SEPARATOR_1          = "::"
  val METADATA_SEPARATOR_2          = ","
  val POLYGONS_PATH_SEPARATOR       = "::"

    def getSparkSession(name:String, consoleEcho:Boolean = true) : SparkSession = {
        val spark = SparkSession
          .builder()
          .appName(name)
          .config("spark.io.compression.codec", "lz4")
          .config("spark.executor.cores", "3")
          .getOrCreate()

        // For implicit conversions
        import spark.implicits._

        if (consoleEcho)
            spark.conf.getAll.foreach{ case (key, value) => println(s"\t$key : $value") }

        spark
    }

    def unionOfPolygonsDfSeq(polygonsDfSeq : Seq[DataFrame], defaultPolygonsDfSeq: Seq[DataFrame]): Seq[DataFrame] = {
      var unionDfs = Seq[DataFrame]()

      for((polygonsDf, defaultPolygonsDf) <- (polygonsDfSeq, defaultPolygonsDfSeq).zipped.toSeq) {
        val resDf = polygonsDf.union(defaultPolygonsDf)

        unionDfs = unionDfs ++ Seq(resDf)
      }

      unionDfs
    }


    /**
      *
      *
      * @param
      * @return
      */
    def aggregateLabelsPerPoint(df: DataFrame, groupByCols: Seq[String], aggregateCols: Seq[String], labelsSeparator: String):  DataFrame = {
        val aggCols = aggregateCols.map(colName => expr(s"""concat_ws(\"$labelsSeparator\",collect_list($colName))""").alias(colName))
        val aggregatedDf =  df
        .groupBy (groupByCols.head, groupByCols.tail: _*)
        .agg(aggCols.head, aggCols.tail: _*)

        aggregatedDf
    }

    /**
      *
      *
      * @param
      * @return
      */
    def countLabelsPerPoint(df: DataFrame, labelColName: String, labelsSeparator: String, countAlias: String) : DataFrame = {
        df.withColumn(countAlias, size(split(col(labelColName), labelsSeparator)))
    }

    /**
      *
      *
      * @param
      * @return
      */
    def removeSelectedLabel(df: DataFrame, labelColName: String, labelsSeparator: String, labelToRemove: String) : DataFrame = {
      // Functionality can be merged to aggregateLabelsPerPoint with optional parameter.
      val removeLabel = udf{(x: String, labelToRemove: String, labelsSeparator: String) =>
        val labelSeq = x.split(labelsSeparator)
        if(labelSeq.length > 1) {
          labelSeq.filterNot(_ == labelToRemove).mkString(labelsSeparator)
        }
        else
          labelSeq.mkString(labelsSeparator)
      }

      df.withColumn(labelColName, removeLabel(df(labelColName), lit(labelToRemove), lit(labelsSeparator)))
    }

    def runJob(
      df:DataFrame, 
      xCol:String,
      yCol:String,
      toGPS:Boolean = true,
      dfPolygons:DataFrame, 
      polygonCol:String = "polygon",
      metadataCols:Option[Seq[String]] = None,
      magellanIndex:Option[Int] = None,
      outPartitions:Option[Int] = None,
      outPath:Option[String] = None
    ) : DataFrame = {

        val MAGELLANPOINT_COL = "_magellanPoint"

        var cols = df.columns.toSeq 

        val dfIn = 
          if (toGPS) {
              cols = cols ++ Seq("lat", "lon")
              CoordinatesUtils.toGPS(df, xCol, yCol)
          }
          else
              df

        if (!metadataCols.isEmpty)
            cols = cols ++ metadataCols.get

        var dfPoints = 
          if (toGPS) 
              dfIn.withColumn(MAGELLANPOINT_COL, CoordinatesUtils.magellanPointUDF(dfIn.col("lon"), dfIn.col("lat")))
          else
              dfIn.withColumn(MAGELLANPOINT_COL, CoordinatesUtils.magellanPointUDF(dfIn.col(xCol), dfIn.col(yCol)))

        if (!magellanIndex.isEmpty) {
            magellan.Utils.injectRules(df.sparkSession)
            dfPoints = dfPoints.index(magellanIndex.get)
        }

        var dfPointsLabeled = dfPoints
          .join(dfPolygons)
          .where(dfPoints.col(MAGELLANPOINT_COL) within dfPolygons.col(polygonCol))
          .select(cols.map(name => col(name)):_*)

        if (!outPartitions.isEmpty)
            dfPointsLabeled = dfPointsLabeled.repartition(outPartitions.get)

        if (outPath.isEmpty == false)
            dfPointsLabeled.write
              .mode(SaveMode.Overwrite)
              .format("com.databricks.spark.csv")
              .option("delimiter", "\t")
              .option("header", "false")
              .csv(outPath.get)

        dfPointsLabeled
    }

    /**
      * TBD
      *
      * @param
      * @return
      */
    def runMultiPolygonJob(
      spark: SparkSession,
      dfIn:DataFrame,
      xColName:String,
      yColName:String,
      toGPS:Boolean = true,
      dfMultiPolygons: Seq[DataFrame],
      polygonCol:String = "polygon",
      metadataColsSeq:Option[Seq[Seq[String]]] = None,
      magellanIndex:Seq[Option[Int]] = Seq(None),
      aggregateLabels:Boolean = false,
      outPartitions:Option[Int] = None,
      outPath:Option[String] = None
    ) : DataFrame = {

        var firstIteration  = true
        var inputDf         = dfIn
        var xColNameIn      = xColName
        var yColNameIn      = yColName
        var toGPSIn         = toGPS
        var combinedDf      = spark.emptyDataFrame
        var groupByCols     = dfIn.columns.toSeq
        if (toGPS) {
          groupByCols = groupByCols ++ Seq("lat", "lon")
        }

        for((dfPolygons, metadataToFilter, index) <- (dfMultiPolygons, metadataColsSeq.get, magellanIndex).zipped.toSeq) {

          val pointsLabeledDf = runJob(inputDf, xColNameIn, yColNameIn, toGPS = toGPSIn, dfPolygons = dfPolygons, metadataCols = Some(metadataToFilter), magellanIndex = index, outPartitions = None, outPath = None)

          val resultDf = if(aggregateLabels) {
            val tmpDf = aggregateLabelsPerPoint(pointsLabeledDf, groupByCols, metadataToFilter, LABELS_SEPARATOR)
            countLabelsPerPoint(tmpDf, metadataToFilter(0), LABELS_SEPARATOR, metadataToFilter(0) + "_Count")
          } else {
            pointsLabeledDf
          }

          // runJob parameters are different from second iteration.
          if(firstIteration) {
            firstIteration = false
            if(toGPS) {
              xColNameIn  = "lon"
              yColNameIn  = "lat"
              toGPSIn     = false
            }
          }

          // Extra variable used, just for proper in and out naming purpose.
          inputDf       = resultDf
          combinedDf    = resultDf
          groupByCols   = resultDf.columns.toSeq

        }

        if (!outPartitions.isEmpty)
            combinedDf = combinedDf.repartition(outPartitions.get)

        if (outPath.isEmpty == false)
          combinedDf.write
            .mode(SaveMode.Overwrite)
            .format("com.databricks.spark.csv")
            .option("delimiter", "\t")
            .option("header", "false")
            .csv(outPath.get)

        combinedDf
    }


    // Scallop config parameters
    class CLOpts(arguments: Seq[String]) extends ScallopConf(arguments) {
      val separator           = opt[String](name="separator", required = false, default=Some("\t"))
      val magellanIndex       = opt[String](name="magellan-index", required = false, default=Some(""))
      val coordsInfo          = opt[String](name="coords-info", required = true)
      val toGPS               = opt[Boolean](name="to-gps", required = false, default=Some(false))
      val polygonsPath        = opt[String](name="polygons-path", required = false, default=Some(""))
      val inPartitions        = opt[Int](name="in-partitions", required = false, default=Some(-1))
      val outPartitions       = opt[Int](name="out-partitions", required = false, default=Some(-1))
      val metadataToFilter    = opt[String](name="metadata-to-filter", required = false, default=Some(""))
      val defaultPolygonsPath = opt[String](name="default-polygons-path", required = false, default=Some(""))
      val aggregateLabels     = opt[Boolean](name="aggregate-labels", required = false, default=Some(false))
      val others              = trailArg[List[String]](required = false)

      validate(magellanIndex, polygonsPath, metadataToFilter) { (index, paths, metadata) =>
        val indexCount    = index.split(MAGELLAN_INDEX_SEPARATOR).length
        val pathsCount    = paths.split(POLYGONS_PATH_SEPARATOR).length
        val metadataCount = metadata.split(METADATA_SEPARATOR_1).length

        if( (indexCount == pathsCount) && (pathsCount == metadataCount))
          Right(Unit)
        else
          Left("Incorrect arguments: Check magellan index, polygons paths and metadata to filter parameters!!!")
      }

      verify()
    }

    /**
      * TBD
      *
      * @param
      * @return
      */
    def convertStringIndexToIntSeq(index: String, separator: String) : Seq[Option[Int]] = {
      val magellanIndex = index.split(separator)

      val retIndex = magellanIndex.map { idx =>
        val magellanIndexInt = if (idx == "") -1 else idx.toInt
        if (magellanIndexInt > 0 && (magellanIndexInt - magellanIndexInt % 5) >= 5)
          Some(magellanIndexInt - (magellanIndexInt % 5))
        else
          None
      }

      retIndex
    }

    def main(args: Array[String]) {

        val clopts = new CLOpts(args)
        val separator           = clopts.separator()
        val inPartitions        = clopts.inPartitions()
        val outPartitions       = if (clopts.outPartitions() == -1) None else Some(clopts.outPartitions())
        val coordsInfo          = clopts.coordsInfo()
        val toGPS               = clopts.toGPS()
        val polygonsPath        = if(clopts.polygonsPath() == "") None else Some(clopts.polygonsPath().split(POLYGONS_PATH_SEPARATOR).toSeq)
        val metadataToFilter    = if (clopts.metadataToFilter() == "") None else Some(clopts.metadataToFilter().split(METADATA_SEPARATOR_1).toSeq)
        val others              = clopts.others()
        val outPath             = others(0)
        val magellanIndex       = convertStringIndexToIntSeq(clopts.magellanIndex(), MAGELLAN_INDEX_SEPARATOR)
        val defaultPolygonsPath = if(clopts.defaultPolygonsPath() == "") None else Some(clopts.defaultPolygonsPath())
        val aggregateLabels     = clopts.aggregateLabels()

        // metadataToFilter type is Option[Seq[String]]. Each element is separated by ",".
        // This need to be converted to Seq[Seq[String]]
        val metadataToFilterSeq = for{
                                      metaData <- metadataToFilter.get
                                  } yield  metaData.split(METADATA_SEPARATOR_2).toSeq

        val tmp = coordsInfo.split("::")
        val xColName   = "_c%s".format(tmp(0))
        val yColName   = "_c%s".format(tmp(1))
        val coordsPath  = tmp(2)

        val spark = getSparkSession("JobGeoMapping")

        val dfIn = spark
          .read
          .format("com.databricks.spark.csv")
          .option("delimiter", separator)
          .option("header", "false")
          .csv(coordsPath) 

        val dfIn2 = 
          if (inPartitions > 0)
              dfIn.repartition(inPartitions)
          else
              dfIn

        var polygonsDfSeq = CoordinatesUtils.loadMultiPolygons(spark, polygonsPath.get, Some(metadataToFilterSeq), magellanIndex)

        if(!defaultPolygonsPath.isEmpty) {
          val defaultPolygonsDfSeq = CoordinatesUtils.loadDefaultPolygons(spark, defaultPolygonsPath.get, Some(metadataToFilterSeq), magellanIndex)

          polygonsDfSeq = unionOfPolygonsDfSeq(polygonsDfSeq, defaultPolygonsDfSeq)
        }

        runMultiPolygonJob(spark, dfIn2, xColName, yColName, toGPS, polygonsDfSeq, "polygon", Some(metadataToFilterSeq), magellanIndex, aggregateLabels, outPartitions = outPartitions, outPath = Some(outPath))
    }
 }
