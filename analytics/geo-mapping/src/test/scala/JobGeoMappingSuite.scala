import com.holdenkarau.spark.testing._
import org.scalatest.FunSuite
import org.apache.spark.sql.types.{StructField, _}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

class JobGeoMappingSuite extends FunSuite with DataFrameSuiteBase {

  override implicit def reuseContextIfPossible: Boolean = true

  import spark.implicits._

  ignore("getSparkSession TBD") {

  }

  test("Magellan Index input check") {

    var inputString = "12,5,-1,19"
    var expectedResult = Seq(Some(10), Some(5), None, Some(15))
    var result = JobGeoMapping.convertStringIndexToIntSeq(inputString, ",")
    assert(result === expectedResult)

    // Different separator case
    inputString = "0#32#-10#6"
    expectedResult = Seq(None, Some(30), None, Some(5))
    result = JobGeoMapping.convertStringIndexToIntSeq(inputString, "#")
    assert(result === expectedResult)

    inputString = "12,,,19"
    expectedResult = Seq(Some(10), None, None, Some(15))
    result = JobGeoMapping.convertStringIndexToIntSeq(inputString, ",")
    assert(result === expectedResult)

    // Exception case
    inputString = "0,32,-10,6,A,"
    assertThrows[NumberFormatException] {
      result = JobGeoMapping.convertStringIndexToIntSeq(inputString, ",")
    }
  }

  test("consolidateMetadata single metadata field") {
    val inputSeq = Seq(
      Row("User1", 53.350, -3.141, "120"),
      Row("User2", 53.373, -3.143, "213"),
      Row("User1", 53.350, -3.141, "125"),
      Row("User2", 53.373, -3.143, "220"),
      Row("User1", 53.350, -3.141, "130"),
      Row("User2", 53.373, -3.143, "230")
    )

    val schema = List(
      StructField("Name", StringType),
      StructField("Lat", DoubleType),
      StructField("Lng", DoubleType),
      StructField("Label1", StringType)
    )

    val dfIn1 = spark.createDataFrame(
      spark.sparkContext.parallelize(inputSeq),
      StructType(schema)
    )

    val groupByCols = Seq("Name", "Lat", "Lng")
    val aggregateCols = Seq("Label1")

    var actualDf = JobGeoMapping.consolidateMetadata(dfIn1, groupByCols, aggregateCols, JobGeoMapping.METADATA_SEPARATOR, None)

    // Sort to keep the order (needed for dataframe comparision)
    actualDf = actualDf.sort("Name")

    val expectedData = Seq(
      Row("User1", 53.350, -3.141, "120,125,130", 3),
      Row("User2", 53.373, -3.143, "213,220,230", 3)
    )

    //TODO: Do we need to have expected schema well?

    val expectedDF = spark.createDataFrame(
      spark.sparkContext.parallelize(expectedData),
      StructType(actualDf.schema)
    ).sort("Name")

    assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
  }

    test("consolidateMetadata multiple metadata fields") {
      val inputSeq = Seq(
        Row("User1", 53.350, -3.141, "120", "Name 1"),
        Row("User2", 53.373, -3.143, "213", "Name 2"),
        Row("User1", 53.350, -3.141, "125", "Name 3"),
        Row("User2", 53.373, -3.143, "220", "Name 2"),
        Row("User1", 53.350, -3.141, "130", "Name 1")
      )

      val schema = List(
        StructField("Name", StringType),
        StructField("Lat", DoubleType),
        StructField("Lng", DoubleType),
        StructField("Label1", StringType),
        StructField("Label2", StringType)
      )

      val dfIn1 = spark.createDataFrame(
        spark.sparkContext.parallelize(inputSeq),
        StructType(schema)
      )

      val groupByCols = Seq("Name", "Lat", "Lng")
      val aggregateCols = Seq("Label1", "Label2")

      var actualDf = JobGeoMapping.consolidateMetadata(dfIn1, groupByCols, aggregateCols, JobGeoMapping.METADATA_SEPARATOR, None)

      // Sort to keep the order (needed for dataframe comparision)
      actualDf = actualDf.sort("Name")

      //TODO: Do we need to have expected schema as well?
      val expectedData = Seq(
        Row("User1", 53.350, -3.141, "120,125,130", "Name 1,Name 3,Name 1", 3),
        Row("User2", 53.373, -3.143, "213,220", "Name 2,Name 2", 2)
      )

      val expectedDF = spark.createDataFrame(
        spark.sparkContext.parallelize(expectedData),
        StructType(actualDf.schema)
      ).sort("Name")

      assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
    }


  test("consolidateMetadata with default metadata") {
    val inputSeq = Seq(
      Row("User1", 53.350, -3.141, "120,", "Name 1,"),
      Row("User2", 53.373, -3.143, "213", "Name 2"),
      Row("User1", 53.350, -3.141, "125", "Name 3"),
      Row("User2", 53.373, -3.143, "220", "Name 2"),
      Row("User1", 53.350, -3.141, "130", "Name 1")
    )

    val schema = List(
      StructField("Name", StringType),
      StructField("Lat", DoubleType),
      StructField("Lng", DoubleType),
      StructField("Label1", StringType),
      StructField("Label2", StringType)
    )

    val dfIn1 = spark.createDataFrame(
      spark.sparkContext.parallelize(inputSeq),
      StructType(schema)
    )

    val groupByCols       = Seq("Name", "Lat", "Lng")
    val aggregateCols     = Seq("Label1", "Label2")
    val defaultMetadata   = ""

    var actualDf = JobGeoMapping.consolidateMetadata(dfIn1, groupByCols, aggregateCols, JobGeoMapping.METADATA_SEPARATOR, Some(defaultMetadata))

    // Sort to keep the order (needed for data frame comparision)
    actualDf = actualDf.sort("Name")

    //TODO: Do we need to have expected schema as well?
    val expectedData = Seq(
      Row("User1", 53.350, -3.141, "120,125,130", "Name 1,Name 3,Name 1", 3),
      Row("User2", 53.373, -3.143, "213,220", "Name 2,Name 2", 2)
    )

    val expectedDF = spark.createDataFrame(
      spark.sparkContext.parallelize(expectedData),
      StructType(actualDf.schema)
    ).sort("Name")

    assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
  }

  test("consolidateMetadata no default metadata") {
    val inputSeq = Seq(
      Row("User1", 53.350, -3.141, "120,", "Name 1,"),
      Row("User2", 53.373, -3.143, "213", "Name 2"),
      Row("User1", 53.350, -3.141, "125", "Name 3"),
      Row("User2", 53.373, -3.143, "220", "Name 2"),
      Row("User1", 53.350, -3.141, "130", "Name 1")
    )

    val schema = List(
      StructField("Name", StringType, false),
      StructField("Lat", DoubleType, false),
      StructField("Lng", DoubleType, false),
      StructField("Label1", StringType, false),
      StructField("Label2", StringType, false)
    )

    val dfIn1 = spark.createDataFrame(
      spark.sparkContext.parallelize(inputSeq),
      StructType(schema)
    )

    val groupByCols       = Seq("Name", "Lat", "Lng")
    val aggregateCols     = Seq("Label1", "Label2")

    var actualDf = JobGeoMapping.consolidateMetadata(dfIn1, groupByCols, aggregateCols, JobGeoMapping.METADATA_SEPARATOR, None)

    // Sort to keep the order (needed for data frame comparision)
    actualDf = actualDf.sort("Name")

    val expectedData = Seq(
      Row("User1", 53.350, -3.141, "120,,125,130", "Name 1,,Name 3,Name 1", 4),
      Row("User2", 53.373, -3.143, "213,220", "Name 2,Name 2", 2)
    )

    val expectedDF = spark.createDataFrame(
      spark.sparkContext.parallelize(expectedData),
      StructType(actualDf.schema)
    ).sort("Name")

    assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
  }


  test("consolidateMetadata incorrect col names") {
    val inputSeq = Seq(
      Row("User1", 53.350, -3.141, "120,", "Name 1,"),
      Row("User2", 53.373, -3.143, "213", "Name 2"),
      Row("User1", 53.350, -3.141, "125", "Name 3"),
      Row("User2", 53.373, -3.143, "220", "Name 2"),
      Row("User1", 53.350, -3.141, "130", "Name 1")
    )

    val schema = List(
      StructField("Name", StringType, false),
      StructField("Lat", DoubleType, false),
      StructField("Lng", DoubleType, false),
      StructField("Label1", StringType, false),
      StructField("Label2", StringType, false)
    )

    val dfIn1 = spark.createDataFrame(
      spark.sparkContext.parallelize(inputSeq),
      StructType(schema)
    )

    //Incorrect groupByCols
    var groupByCols = Seq("Name11", "Lat", "Lng")
    var aggregateCols = Seq("Label1", "Label2")

    assertThrows[org.apache.spark.sql.AnalysisException] {
      JobGeoMapping.consolidateMetadata(dfIn1, groupByCols, aggregateCols, JobGeoMapping.METADATA_SEPARATOR, None)
    }

    //Incorrect aggregateCols
    groupByCols = Seq("Name", "Lat", "Lng")
    aggregateCols = Seq("Label111", "Label2")

    assertThrows[org.apache.spark.sql.AnalysisException] {
      JobGeoMapping.consolidateMetadata(dfIn1, groupByCols, aggregateCols, JobGeoMapping.METADATA_SEPARATOR, None)
    }
  }

/*
    test("runMultiPolygonsJob test 1 (Single polygon)") {

      /* This test case covers toGPS, Aggregate labels and job works with single polygons set.     * */

      val inputData = Seq(
        Row("User1", 324136.095, 384397.104),
        Row("User2", 324011.005, 386869.185),
        Row("User3", 325009.696, 386295.83)
      )

      val schema = List(
        StructField("Name", StringType,false),
        StructField("xCentroid", DoubleType,false),
        StructField("yCentroid", DoubleType,false)
      )

      val dfIn = spark.createDataFrame(
        spark.sparkContext.parallelize(inputData),
        StructType(schema)
      )

      val xColName           = "xCentroid"
      val yColName           = "yCentroid"
      val polygonsPath       = this.getClass.getClassLoader.getResource("geojson/beacon_gps_sample.geojson").getPath
      val metadataToExtract  = Seq("UID")
      val magellanIndex      = Some(5)
      val aggregateLabels    = true
      val polygonCol         = "polygon"

      val dfMultiPolygons    = JobGeoMapping.loadMultiPolygons(spark, Seq(polygonsPath), Some(metadataToExtract), Seq(magellanIndex), None)
      var actualDf           = JobGeoMapping.runMultiPolygonJob(dfIn, xColName, yColName, true, dfMultiPolygons, polygonCol, Some(metadataToExtract), Seq(magellanIndex), aggregateLabels, None, None)

      // Sort to keep the order (needed for dataframe comparision)
      actualDf = actualDf.sort("Name")

      val expectedSchema = List(
        StructField("Name", StringType,false),
        StructField("xCentroid", DoubleType, false),
        StructField("yCentroid" ,DoubleType, false),
        StructField("lat",DoubleType, true),
        StructField("lon", DoubleType, true),
        StructField("UID", StringType, false),
        StructField("UID_Count", IntegerType, false)
      )

      val expectedData = Seq(
        Row("User1", 324136.095, 384397.104, 53.350996777067465,  -3.141149882762535, "213", 1),
        Row("User2", 324011.005, 386869.185, 53.373194945386025, -3.1436235641372563, "213", 1),
        Row("User3", 325009.696, 386295.83, 53.36818516353612, -3.128479626392792, "120,213", 2)
      )

      val expectedDF = spark.createDataFrame(
        spark.sparkContext.parallelize(expectedData),
        StructType(expectedSchema)
      ).sort("Name")

      assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
    }


    test("runMultiPolygonsJob test 2 (multi polygon)") {

      val inputData = Seq(
        Row("User1", 324136.095, 384397.104),
        Row("User2", 324011.005, 386869.185),
        Row("User3", 325009.696, 386295.83)
      )

      val schema = List(
        StructField("Name", StringType,false),
        StructField("xCentroid", DoubleType,false),
        StructField("yCentroid", DoubleType,false)
      )

      val dfIn = spark.createDataFrame(
        spark.sparkContext.parallelize(inputData),
        StructType(schema)
      )

      val xColName           = "xCentroid"
      val yColName           = "yCentroid"
      val polygonsPath1      = this.getClass.getClassLoader.getResource("geojson/beacon_gps_sample.geojson").getPath
      val polygonsPath2      = this.getClass.getClassLoader.getResource("geojson/postdist_gps_sample.geojson").getPath
      val polygonsSeq        = Seq(polygonsPath1, polygonsPath2)
      val metadataToExtract  = Seq("UID", "Postdist,Postarea")
      val magellanIndex      = Seq(None, Some(5))
      val aggregateLabels    = true
      val polygonCol         = "polygon"
      val defaultPolygons    = None
      val toGPS              = true

      val dfMultiPolygons    = JobGeoMapping.loadMultiPolygons(spark, polygonsSeq, Some(metadataToExtract), magellanIndex, defaultPolygons)
      var actualDf           = JobGeoMapping.runMultiPolygonJob(dfIn, xColName, yColName, toGPS, dfMultiPolygons, polygonCol, Some(metadataToExtract), magellanIndex, aggregateLabels, None, None)

      // Sort to keep the order (needed for dataframe comparision)
      actualDf = actualDf.sort("Name")

      val expectedSchema = List(
        StructField("Name", StringType, true),
        StructField("xCentroid", DoubleType, true),
        StructField("yCentroid", DoubleType, true),
        StructField("lat", DoubleType, true),
        StructField("lon", DoubleType, true),
        StructField("UID", StringType, true),
        StructField("UID_Count", IntegerType, true),
        StructField("Postdist", StringType, true),
        StructField("Postarea", StringType, true),
        StructField("Postdist_Count", IntegerType, true)
      )

      val expectedData = Seq(
        Row("User1", 324136.095, 384397.104, 53.350996777067465, -3.141149882762535, "213", 1, "CH48", "CH", 1),
        Row("User2", 324011.005, 386869.185, 53.373194945386025, -3.1436235641372563, "213", 1, "CH48", "CH", 1),
        Row("User3", 325009.696, 386295.83, 53.36818516353612, -3.128479626392792, "120,213", 2, "CH48", "CH", 1)
      )

      val expectedDF = spark.createDataFrame(
        spark.sparkContext.parallelize(expectedData),
        StructType(expectedSchema)
      ).sort("Name")

      assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
    }

    test("runMultiPolygonsJob test 3 (no GPS/aggregate)") {

      val inputData = Seq(
        Row("User1", -3.141149882762535, 53.350996777067465),
        Row("User2", -3.1436235641372563, 53.373194945386025),
        Row("User3", -3.128479626392792, 53.36818516353612)
      )

      val schema = List(
        StructField("Name", StringType,false),
        StructField("Lon", DoubleType,false),
        StructField("Lat", DoubleType,false)
      )

      val dfIn = spark.createDataFrame(
        spark.sparkContext.parallelize(inputData),
        StructType(schema)
      )

      val xColName           = "Lon"
      val yColName           = "Lat"
      val polygonsPath1      = this.getClass.getClassLoader.getResource("geojson/beacon_gps_sample.geojson").getPath
      val polygonsPath2      = this.getClass.getClassLoader.getResource("geojson/postdist_gps_sample.geojson").getPath
      val polygonsSeq        = Seq(polygonsPath1, polygonsPath2)
      val metadataToExtract  = Seq("UID", "Postdist,Postarea")
      val magellanIndex      = Seq(None, Some(5))
      val aggregateLabels    = false
      val polygonCol         = "polygon"
      val defaultPolygons    = None
      val toGPS              = false

      val dfMultiPolygons    = JobGeoMapping.loadMultiPolygons(spark, polygonsSeq, Some(metadataToExtract), magellanIndex, defaultPolygons)
      var actualDf           = JobGeoMapping.runMultiPolygonJob(dfIn, xColName, yColName, toGPS, dfMultiPolygons, polygonCol, Some(metadataToExtract), magellanIndex, aggregateLabels, None, None)

      // Sort to keep the order (needed for dataframe comparision)
      actualDf = actualDf.sort("Name")

      val expectedSchema = List(
        StructField("Name", StringType, true),
        StructField("Lon", DoubleType, true),
        StructField("Lat", DoubleType, true),
        StructField("UID", StringType, true),
        StructField("Postdist", StringType, true),
        StructField("Postarea", StringType, true)
        )

      val expectedData = Seq(
       Row("User1", -3.141149882762535, 53.350996777067465, "213", "CH48", "CH"),
       Row("User2", -3.1436235641372563, 53.373194945386025, "213", "CH48", "CH"),
       Row("User3", -3.128479626392792, 53.36818516353612, "120", "CH48", "CH"),
       Row("User3", -3.128479626392792, 53.36818516353612, "213", "CH48", "CH")
      )

      val expectedDF = spark.createDataFrame(
        spark.sparkContext.parallelize(expectedData),
        StructType(expectedSchema)
      ).sort("Name")

      assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
    }

    test("runMultiPolygonsJob test 4 (default polygons set)") {

      val inputData = Seq(
        Row("User1", -3.141149882762535, 53.350996777067465),
        Row("User2", -3.1436235641372563, 53.373194945386025),
        Row("User3", -3.128479626392792, 53.36818516353612)
      )

      val schema = List(
        StructField("Name", StringType,false),
        StructField("Lon", DoubleType,false),
        StructField("Lat", DoubleType,false)
      )

      val dfIn = spark.createDataFrame(
        spark.sparkContext.parallelize(inputData),
        StructType(schema)
      )

      val xColName           = "Lon"
      val yColName           = "Lat"
      val polygonsPath1      = this.getClass.getClassLoader.getResource("geojson/beacon_gps_sample.geojson").getPath
      val polygonsPath2      = this.getClass.getClassLoader.getResource("geojson/postdist_gps_sample.geojson").getPath
      val polygonsSeq        = Seq(polygonsPath1, polygonsPath2)
      val metadataToExtract  = Seq("UID", "Postdist,Postarea")
      val magellanIndex      = Seq(None, Some(5))
      val aggregateLabels    = true
      val polygonCol         = "polygon"
      val defaultPolygonsPath = this.getClass.getClassLoader.getResource("geojson/defaultPolygon.geojson").getPath
      val toGPS              = false

      val dfMultiPolygons    = JobGeoMapping.loadMultiPolygons(spark, polygonsSeq, Some(metadataToExtract), magellanIndex, Some(defaultPolygonsPath))
      var actualDf           = JobGeoMapping.runMultiPolygonJob(dfIn, xColName, yColName, toGPS, dfMultiPolygons, polygonCol, Some(metadataToExtract), magellanIndex, aggregateLabels, None, None)

      // Sort to keep the order (needed for dataframe comparision)
      actualDf = actualDf.sort("Name")

      val expectedSchema = List(
        StructField("Name", StringType, true),
        StructField("Lon", DoubleType, true),
        StructField("Lat", DoubleType, true),
        StructField("UID", StringType, true),
        StructField("UID_Count", IntegerType, true),
        StructField("Postdist", StringType, true),
        StructField("Postarea", StringType, true),
        StructField("Postdist_Count", IntegerType, true)
      )

      val expectedData = Seq(
        Row("User1", -3.141149882762535, 53.350996777067465, "213,", 2, "CH48,", "CH,", 2),
        Row("User2", -3.1436235641372563, 53.373194945386025, "213,", 2, "CH48,", "CH,", 2),
        Row("User3", -3.128479626392792, 53.36818516353612, "120,213,", 3, "CH48,", "CH,", 2)
      )

      val expectedDF = spark.createDataFrame(
        spark.sparkContext.parallelize(expectedData),
        StructType(expectedSchema)
      ).sort("Name")

      assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
    }

    test("removeSelectedLabel test") {
      val inputData = Seq(
        Row("User1", -3.141149882762535, 53.350996777067465, "213,", 2, "CH48,", "CH,", 2),
        Row("User2", -3.1436235641372563, 53.373194945386025, "213,", 2, "CH48,", "CH,", 2),
        Row("User3", -3.128479626392792, 53.36818516353612, "120,213,", 3, "CH48,", "CH,", 2)
      )

      val schema = List(
        StructField("Name", StringType, true),
        StructField("Lon", DoubleType, true),
        StructField("Lat", DoubleType, true),
        StructField("UID", StringType, true),
        StructField("UID_Count", IntegerType, true),
        StructField("Postdist", StringType, true),
        StructField("Postarea", StringType, true),
        StructField("Postdist_Count", IntegerType, true)
      )

      val dfIn = spark.createDataFrame(
        spark.sparkContext.parallelize(inputData),
        StructType(schema)
      )

      var actualDf = JobGeoMapping.removeSelectedLabel(dfIn, "UID", ",", "")
      actualDf = JobGeoMapping.removeSelectedLabel(actualDf, "Postdist", ",", "")
      actualDf = JobGeoMapping.removeSelectedLabel(actualDf, "Postarea", ",", "")

      // Sort to keep the order (needed for dataframe comparision)
      actualDf = actualDf.sort("Name")

      val expectedData = Seq(
        Row("User1", -3.141149882762535, 53.350996777067465, "213", 2, "CH48", "CH", 2),
        Row("User2", -3.1436235641372563, 53.373194945386025, "213", 2, "CH48", "CH", 2),
        Row("User3", -3.128479626392792, 53.36818516353612, "120,213", 3, "CH48", "CH", 2)
      )

      val expectedDF = spark.createDataFrame(
        spark.sparkContext.parallelize(expectedData),
        StructType(schema)
      ).sort("Name")

      assertDataFrameApproximateEquals(expectedDF, actualDf, 0.005)
    }*/
}
