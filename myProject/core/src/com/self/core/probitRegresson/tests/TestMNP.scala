package com.self.core.probitRegresson.tests

import com.self.core.baseApp.myAPP
import org.apache.spark.mllib.classification.Probit
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}

import scala.collection.mutable.ArrayBuffer

object TestMNP extends myAPP {
  def simulateData(): Unit = {
    val rawDataFrame: DataFrame = TestData.simulateMulti(sc, sqlc)
    outputrdd.put("tableName", rawDataFrame)
  }


  override def run(): Unit = {
    // 生成数据到缓存
    simulateData()

    /**
      * 一些参数的处理
      */
    /** 0)获取基本的系统变量 */
    //    val jsonparam = "<#zzjzParam#>"
    //    val gson = new Gson()
    //    val p: java.util.Map[String, String] = gson.fromJson(jsonparam, classOf[java.util.Map[String, String]])
    //    val z1 = z
    val rddTableName = "<#zzjzRddName#>"
    val z1 = outputrdd

    /** 1)获取DataFrame */
    val tableName = "tableName"

    /** 参数配置 */
    val rawDataDF = z1.get("tableName").asInstanceOf[DataFrame]
    //    val rawDataDF = z1.rdd(tableName).asInstanceOf[org.apache.spark.sql.DataFrame]


    /** 2)获取对应的特征列名 */
    var featuresSchema = ArrayBuffer.empty[(String, String)]
    //    val featuresArr = pJsonParser.getAsJsonArray("features")
    //    for (i <- 0 until featuresArr.size()) {
    //      val featureObj = featuresArr.get(i).getAsJsonObject
    //      val tup = (featureObj.get("name").getAsString, featureObj.get("datatype").getAsString)
    //      featuresSchema += tup
    //    }
    featuresSchema = featuresSchema :+ Tuple2("x1", "double") :+ Tuple2("x2", "double")

    /** 3)获取对应的标签类名信息 */
    //    val labelObj = pJsonParser.getAsJsonArray("label").get(0).getAsJsonObject
    val (labelName, labelDataType) = ("y", "double")
    println(rawDataDF.schema.map(_.name).mkString(","))

    /** 4)数据转换 */
    val sche = rawDataDF.schema
    val getIndex = (name: String) => try {
      sche.fieldIndex(name)
    } catch {
      case _: Exception => throw new Exception(s"没有找到列名$name")
    }


    val trainData: RDD[LabeledPoint] = rawDataDF.rdd.map(row => {
      val arr = featuresSchema.map {
        case (name, dataType) =>
          dataType match {
            case "string" => row.getAs[String](name).toInt.toDouble
            case "int" => if (row.isNullAt(getIndex(name))) Double.NaN else row.getAs[Int](name).toDouble
            case "double" => if (row.isNullAt(getIndex(name))) Double.NaN else row.getAs[Double](name).toInt.toDouble
            case "float" => if (row.isNullAt(getIndex(name))) Double.NaN else row.getAs[Float](name).toInt.toDouble
            case "long" => if (row.isNullAt(getIndex(name))) Double.NaN else row.getAs[Long](name).toDouble
            case "boolean" => if (row.isNullAt(getIndex(name))) Double.NaN else if (row.getAs[Boolean](name)) 1.0 else 0.0
            case _ => throw new Exception(
              "目前支持string、int、double、float、long以及boolean类型的特征字段")
          }
      }.toArray

      val label = labelDataType match {
        case "string" => if (row.isNullAt(getIndex(labelName))) Double.NaN else row.getAs[String](labelName).toInt.toDouble
        case "int" => if (row.isNullAt(getIndex(labelName))) Double.NaN else row.getAs[Int](labelName).toDouble
        case "double" => if (row.isNullAt(getIndex(labelName))) Double.NaN else row.getAs[Double](labelName).toInt.toDouble
        case "float" => if (row.isNullAt(getIndex(labelName))) Double.NaN else row.getAs[Float](labelName).toInt.toDouble
        case "long" => if (row.isNullAt(getIndex(labelName))) Double.NaN else row.getAs[Long](labelName).toDouble
        case "boolean" => if (row.isNullAt(getIndex(labelName))) Double.NaN else if (row.getAs[Boolean](labelName)) 1.0 else 0.0
        case _ => throw new Exception(
          "目前支持string、int、double、float、long以及boolean类型的特征字段")
      }

      LabeledPoint(label, new DenseVector(arr))
    }).filter(labelPoint => !labelPoint.label.isNaN)

    trainData.cache()

    trainData.foreach(println)

    /** 5)获得分类个数的信息 */
    var numClasses = 0
    val keysCount = trainData.map(u => (u.label, 1)).reduceByKey(_ + _)
    numClasses = keysCount.keys.max().toInt + 1


    if (keysCount.count() >= 100 || numClasses >= 100) {
      throw new Exception("目前最多支持分类100个，请您检查是否是从0开始记入分类类别，或者您的标签列类别是否多于100")
    }

    val featureDims = trainData.first().features.size
    val freedomNum = numClasses * (numClasses + 1) / 2 + featureDims
    if (keysCount.values.sum() <= numClasses * (numClasses + 1) / 2 + featureDims)
      throw new Exception(s"数据数目不够识别所有参数，数据应该多于自由度${freedomNum}才能满足过度识别")

    println("classes:", numClasses)

    /** 数据处理 */
    //    val optimizationOptionObj = pJsonParser.getAsJsonObject("optimizationOption")
    val optimizationOption = "SGD"
    val probitModel = optimizationOption match {
      case "SGD" =>
        val numIterations: Int = try {
          val numString = "200"
          if (numString.eq(null)) 200 else numString.toInt
        } catch {
          case _: Exception => throw new Exception("没有找到最大迭代次数的信息")
        }

        val stepSize: Double = try {
          val stepSizeString = "1.0"
          val learningRate = if (stepSizeString.eq(null)) 1.0 else stepSizeString.toDouble
          require(learningRate <= 1.0 && learningRate >= 0.0, "学习率需要在0到1之间")
          learningRate
        } catch {
          case _: Exception => throw new Exception("学习率信息异常")
        }

        val miniBatchFraction: Double = try {
          val stepSizeString = "0.5"
          val fraction = if (stepSizeString.eq(null)) 1.0 else stepSizeString.toDouble
          require(fraction <= 1.0 && fraction >= 0.0, "随机批次下降占比需要在0到1中间")
          fraction
        } catch {
          case _: Exception => throw new Exception("学习率信息异常")
        }

        val addIntercept = try {
          if ("false" == "true")
            true
          else
            false
        } catch {
          case _: Exception => throw new Exception("截距信息没有获得")
        }

        Probit.trainWithSGD(trainData, numClasses, numIterations, stepSize,
          miniBatchFraction, addIntercept)

      case "LBFGS" =>
        val addIntercept = try {
          if ("true" == "true")
            true
          else
            false
        } catch {
          case _: Exception => throw new Exception("截距信息没有获得")
        }
        Probit.trainWithLBFGS(trainData, numClasses, addIntercept)
    }

    val probitModelBC = rawDataDF.sqlContext.sparkContext.broadcast(probitModel).value

    val resultRdd = trainData.map(labeledPoint =>
      labeledPoint.features.toDense.values
        :+ labeledPoint.label
        :+ probitModelBC.predict(labeledPoint.features)).map(Row.fromSeq(_))

    val schema = featuresSchema.map(s => StructField(s._1, DoubleType)).toArray

    var newDataDF = rawDataDF.sqlContext.createDataFrame(resultRdd,
      StructType(schema :+ StructField(labelName, DoubleType)
        :+ StructField(labelName + "_fit", DoubleType)))

    for (each <- featuresSchema) {
      newDataDF = newDataDF.withColumn(each._1, col(each._1).cast(each._2))
    }

    newDataDF = newDataDF.withColumn(labelName, col(labelName).cast(labelDataType))
      .withColumn(labelName + "_fit", col(labelName + "_fit").cast(labelDataType))


    /** 打印参数信息 */
    println("coefficient:")
    println(probitModel.weights.toDense.values.mkString(", "))
    println("intercept:")
    println(probitModel.intercept)


    /** 输出结果 */
    newDataDF.show()






    //    rawDataFrame.show()
    //
    //    //    val publicFeatures = Array(("收入", "double"))
    //    val features = ArrayBuffer(("评分", "double"), ("距离", "double"))
    //    //
    //    val (labelName, labelType) = ("选择商场", "double")
    //    //    val P = (J - 1) * publicFeatures.length + characterFeatures.length
    //    val labelId = rawDataFrame.schema.fieldIndex(labelName)
    //    val featuresMap = features.map {
    //      case (name, dataType) => (rawDataFrame.schema.fieldIndex(name), dataType)
    //    }
    //
    //    val rawData: RDD[LabeledPoint] = rawDataFrame.rdd.map(row => {
    //      val features = featuresMap.map { case (id, dataType) =>
    //        if (row.isNullAt(id))
    //          Double.NaN
    //        else {
    //          dataType match {
    //            case "string" => row.getAs[String](id).toDouble
    //            case "long" => row.getAs[Long](id).toDouble
    //            case "int" => row.getAs[Int](id).toDouble
    //            case "double" => row.getAs[Double](id)
    //          }
    //        }
    //      }
    //      val label =
    //        if (row.isNullAt(labelId))
    //          Double.NaN
    //        else {
    //          labelType match {
    //            case "string" => row.getAs[String](labelId).toDouble
    //            case "long" => row.getAs[Long](labelId).toDouble
    //            case "int" => row.getAs[Int](labelId).toDouble
    //            case "double" => row.getAs[Double](labelId)
    //          }
    //        }
    //
    //      LabeledPoint(label, new DenseVector(features.toArray))
    //    })


  }
}
