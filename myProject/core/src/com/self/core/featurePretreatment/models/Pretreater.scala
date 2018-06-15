package com.self.core.featurePretreatment.models


import org.apache.spark.ml.feature.StringIndexerModel
import org.apache.spark.sql.DataFrame

import scala.reflect.ClassTag


/**
  * editor: xuhao
  * date: 2018-06-08 08:30:00
  */

/**
  * 特征预处理的抽象类
  * ----
  * 脚本设计思路：
  * 1）实现了实际中预处理的算法；
  * 2）这些算法的[输入数据]和[输出数据]很相似（都至少一个DataFrame）；
  * 3）这些算法的[运行函数]和[参数]差异很大。
  * 因此 ->
  * 1）将[数据]、[输出函数run()]写在抽象类中；
  * 2）将[参数]、[运行函数]作为接口；
  * 3）为了预备将来可能加入新的方法，这里将输出类型做成了一个泛型（虽然目前只有一种输出———DataFrame）。
  * ----
  * 具体来讲，该脚本提供了：
  * 1)实现方法：设定参数和获取参数信息 @see [[setParams]] @see [[getParams]]
  * 2)实现方法：最后统一的输出 @see [[run()]]
  * 2)接口：运行函数的接口 @see [[runAlgorithm()]]
  * 3)接口：判定每个参数是否合法，@see [[setParams]]中的参数[checkParam]，该参数是一个函数
  * 4)接口：判定整个参数组是否满足应有的约束，@see [[paramsValid]]
  *
  * @param data 输入的主要变换的data(还可能有其他DataFrame输出)
  */
abstract class Pretreater[M <: PretreatmentOutput](val data: DataFrame) {

  /**
    * 参数组列表
    * 有一些非常常用的参数，约定一下参数名：
    * inputCol -> 输入列名
    * outputCol -> 输出列名
    */
  val params: java.util.HashMap[String, Any] = new java.util.HashMap[String, Any]()


  /** 参数信息以[[ParamInfo]]的形式传入 */
  final def setParams(paramInfo: ParamInfo, param: Any): this.type = {
    this.params.put(paramInfo.name, param)
    this
  }

  /** 参数信息以[[ParamInfo]]的形式传入 */
  final def setParams(paramInfo: ParamInfo,
                      param: Any,
                      checkParam: Function3[String, Any, String, Boolean] = (_, _, _) => true): this.type = {
    require(checkParam(paramInfo.name, param, paramInfo.annotation), s"您输入的参数不合法：${paramInfo.annotation}")
    this.params.put(paramInfo.name, param)
    this
  }


  /** 传入的参数信息为[[ParamInfo]]的形式 */
  final def getParams[T <: Any : ClassTag](paramInfo: ParamInfo): T = {

    lazy val exception = new IllegalArgumentException(s"您没有找到参数${paramInfo.annotation}")
    val value = this.params.getOrDefault(paramInfo.name, exception)
    lazy val castTypeException = (msg: String) =>
      new ClassCastException(s"${paramInfo.annotation} 不能转为指定类型${}, 具体信息: $msg")
    try {
      value.asInstanceOf[T]
    } catch {
      case e: Exception => throw castTypeException(e.getMessage)
    }
  }


  final def getParamsOrElse[T <: Any : ClassTag](paramInfo: ParamInfo, other: T): T = {
    val value = this.params.getOrDefault(paramInfo.name, other).asInstanceOf[T]
    lazy val castTypeException = (msg: String) =>
      new ClassCastException(s"${paramInfo.annotation} 不能转为指定类型${}, 具体信息: $msg")
    try {
      value.asInstanceOf[T]
    } catch {
      case e: Exception => throw castTypeException(e.getMessage)
    }
  }


  protected def paramsValid(): Boolean


  /**
    * [输出函数run()]
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    * @see [[com.self.core.featurePretreatment.models.PretreatmentOutput]]
    *      里面至少有一个输出的DataFrame，还可能有一个额外的输出信息。
    */
  final def run(): M = {
    require(data.schema.fieldNames.distinct.length == data.schema.size,
      "数据中有模糊列名会影响后续操作, 请检查是否有同名列") // 检查数据是否有模糊列名
    paramsValid() // 检查参数组信息是否合法
    runAlgorithm() // 执行运行函数
  }

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  protected def runAlgorithm(): M

}

/**
  * 正则表达式分词
  */
class TokenizerByRegex(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.RegexTokenizer

    val inputCol = getParams[String](TokenizerByRegexParamsName.inputCol)
    val outputCol = getParams[String](TokenizerByRegexParamsName.outputCol)
    val gaps = getParamsOrElse[Boolean](TokenizerByRegexParamsName.gaps, true)
    val toLowerCase = getParamsOrElse[Boolean](TokenizerByRegexParamsName.toLowerCase, false)
    val pattern = getParamsOrElse[String](TokenizerByRegexParamsName.pattern, "\\s+")
    val minTokenLength = getParamsOrElse[Int](TokenizerByRegexParamsName.minTokenLength, 0)

    val tokenizer = new RegexTokenizer()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setGaps(gaps)
      .setToLowercase(toLowerCase)
      .setPattern(pattern)
      .setMinTokenLength(minTokenLength)
    val wordsData = try {
      tokenizer.transform(this.data)
    } catch {
      case e: Exception => throw new Exception("正则表达式分词转换过程中出现异常，" +
        s"请检查是否是正则表达式或者一些参数发生了错误，${e.getMessage}")
    }
    new UnaryOutput(wordsData)
  }
}


class CountWordVector(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  /** 2-2 向量计数器 */
  override protected def paramsValid(): Boolean = true

  override protected def runAlgorithm(): UnaryOutput = {

    import org.apache.spark.ml.feature.{CountVectorizer, CountVectorizerModel}

    val inputCol = getParams[String](CountWordVectorParamsName.inputCol)
    val outputCol = getParams[String](CountWordVectorParamsName.outputCol)
    val loadModel = getParamsOrElse[Boolean](CountWordVectorParamsName.loadModel, false)

    val cvModel = if (loadModel) {
      val loadPath = getParams[String](CountWordVectorParamsName.loadPath)
      CountVectorizerModel.load(loadPath)
    } else {
      val trainData = getParamsOrElse[DataFrame](CountWordVectorParamsName.trainData, data) // 如果不输入默认训练数据就是预测数据

      val trainInputCol = getParamsOrElse[String](CountWordVectorParamsName.trainInputCol,
        CountWordVectorParamsName.inputCol.name) // 如果不输入默认训练数据就是预测数据

      val trainOutputCol = getParamsOrElse[String](CountWordVectorParamsName.trainOutputCol,
        CountWordVectorParamsName.outputCol.name) // 如果不输入默认训练数据就是预测数据

      val vocabSize = getParamsOrElse[Int](CountWordVectorParamsName.vocabSize, 1 << 18) // 默认 2^18
      val minDf = getParamsOrElse[Double](CountWordVectorParamsName.minDf, 1.0) // 默认1.0
      val minTf = getParamsOrElse[Double](CountWordVectorParamsName.minTf, 1.0) // 默认1.0 这里会设置一次，因为可能持久化

      val model = new CountVectorizer()
        .setInputCol(trainInputCol)
        .setOutputCol(trainOutputCol)
        .setVocabSize(vocabSize)
        .setMinDF(minDf)
        .setMinTF(minTf)
        .fit(trainData)

      val saveModel = getParamsOrElse[Boolean](CountWordVectorParamsName.saveModel, false) // 默认不保存
      val savePath = getParams[String](CountWordVectorParamsName.savePath) // if 保存 --必须输入
      if (saveModel)
        model.save(savePath)

      model
    }

    val minTf = getParams[Double](CountWordVectorParamsName.minTf)
    val newDataFrame = cvModel
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setMinTF(minTf)
      .transform(data)
    new UnaryOutput(newDataFrame)
  }

}


class HashTF(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {

  import org.apache.spark.ml.feature.HashingTF


  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {


    val inputCol = getParams[String](HashTFParamsName.inputCol)
    val outputCol = getParams[String](HashTFParamsName.outputCol)
    val numFeatures = getParamsOrElse[Int](HashTFParamsName.numFeatures, 1 << 18) // 默认 1 << 18, 不能超过int的最大值

    val tfDataFrame = new HashingTF()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setNumFeatures(numFeatures)
      .transform(data)
    new UnaryOutput(tfDataFrame)
  }

}


class WordToVector(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {

  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.{Word2Vec, Word2VecModel}

    val inputCol = getParams[String](WordToVectorParamsName.inputCol) // 这个需要在模型中再设置一次
    val outputCol = getParams[String](WordToVectorParamsName.outputCol)
    val loadModel = getParamsOrElse[Boolean](WordToVectorParamsName.loadModel, false)

    val wvModel = if (loadModel) {
      val loadPath = getParams[String](WordToVectorParamsName.loadPath)
      Word2VecModel.load(loadPath)
    } else {
      val trainData = getParamsOrElse[DataFrame](WordToVectorParamsName.trainData, data)
      // 如果不输入默认训练数据就是预测数据

      val trainInputCol = getParamsOrElse[String](WordToVectorParamsName.trainInputCol,
        inputCol) // 如果不输入默认训练数据就是预测数据

      val trainOutputCol = getParamsOrElse[String](WordToVectorParamsName.trainOutputCol,
        outputCol) // 如果不输入默认训练数据就是预测数据

      val rdSeed = new java.util.Random().nextLong()

      val vocabSize = getParamsOrElse[Int](WordToVectorParamsName.vocabSize, 1 << 18) // 默认 2^18
      val windowSize = getParamsOrElse[Int](WordToVectorParamsName.windowSize, 5) // 默认1.0
      val stepSize = getParamsOrElse[Double](WordToVectorParamsName.stepSize, 0.025) // 默认1.0 这里会设置一次，因为可能持久化
      val numPartitions = getParamsOrElse[Int](WordToVectorParamsName.numPartitions, 1) // 默认1.0 这里会设置一次，因为可能持久化
      val numIterations = getParamsOrElse[Int](WordToVectorParamsName.numIterations, 1) // 默认1.0 这里会设置一次，因为可能持久化
      val minCount = getParamsOrElse[Int](WordToVectorParamsName.minCount, 5)
      val seed = getParamsOrElse[Long](WordToVectorParamsName.seed, rdSeed)

      val model = new Word2Vec()
        .setInputCol(trainInputCol)
        .setOutputCol(trainOutputCol)
        .setVectorSize(vocabSize)
        .setMinCount(minCount)
        .setMaxIter(numIterations)
        .setWindowSize(windowSize)
        .setSeed(seed)
        .setNumPartitions(numPartitions)
        .setStepSize(stepSize)
        .fit(trainData)

      val saveModel = getParamsOrElse[Boolean](WordToVectorParamsName.saveModel, false) // 默认不保存
      val savePath = getParamsOrElse[String](WordToVectorParamsName.savePath, "/data/wordToVectorModel/") // if 保存 --必须输入
      if (saveModel)
        model.save(savePath)

      model
    }

    val newDataFrame = wvModel
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .transform(data)
    new UnaryOutput(newDataFrame)
  }

  override protected def paramsValid(): Boolean = true
}


class StopWordsRmv(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.StopWordsRemover

    val inputCol = getParams[String](StopWordsRemoverParamsName.inputCol)
    val outputCol = getParams[String](StopWordsRemoverParamsName.outputCol)
    val caseSensitive = getParamsOrElse[Boolean](StopWordsRemoverParamsName.caseSensitive, false)
    val stopWords = getParams[Array[String]](StopWordsRemoverParamsName.stopWords) // 可以手动、或者选择英语或汉语
    // 英语：StopWords.English
    // 汉语：
    val newDataFrame = new StopWordsRemover()
      .setInputCol(inputCol)
      .setOutputCol(outputCol).setCaseSensitive(caseSensitive)
      .setStopWords(stopWords) // 停用词 StopWords.English
      .transform(data)

    new UnaryOutput(newDataFrame)

  }
}

class NGramMD(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.NGram

    val inputCol = getParams[String](NGramParamsName.inputCol)
    val outputCol = getParams[String](NGramParamsName.outputCol)
    val n = getParamsOrElse[Int](NGramParamsName.n, 3)

    val newDataFrame = new NGram()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setN(n)
      .transform(data)
    new UnaryOutput(newDataFrame)
  }
}

class Discretizer(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */

  def checkFrameSize(boxesNum: Long): Boolean = {
    val akkaFrameSize = util.Try(
      data.sqlContext.sparkContext.getConf.getSizeAsMb("spark.akka.frameSize")).toOption
    if (akkaFrameSize.isDefined) {
      val size = (1 << 17).toLong * akkaFrameSize.get // 17 = 20 - 3, a double = 2^3 Bytes
      size > boxesNum
    } else {
      println("没有获得akka.frameSize的参数，未能判断等深或自定义的分箱边界是否超过结点间传输限制。")
      true
    }
  }

  override protected def runAlgorithm(): UnaryOutput = {

    val inputCol = getParams[String](DiscretizerParams.inputCol)
    val outputCol = getParams[String](DiscretizerParams.outputCol)
    val discretizeFormat = getParams[String](DiscretizerParams.discretizeFormat)

    // 分为等宽[byWidth]、等深[byDepth]、自定义[selfDefined]

    discretizeFormat match {
      case "byWidth" =>
        val phase = getParams[Double](DiscretizerParams.phase)
        val width = getParams[Double](DiscretizerParams.width)
        require(width > 0, "等宽分箱宽度需要大于0")

        import org.apache.spark.sql.NullableFunctions.udf
        import org.apache.spark.sql.functions.col
        val binningByWidth = udf((d: Double) => scala.math.floor((d - phase) / width))
        new UnaryOutput(data.withColumn(outputCol, binningByWidth(col(inputCol))))

      case "byDepth" =>
        import org.apache.spark.ml.feature.QuantileDiscretizer
        val depth = getParamsOrElse[Double](DiscretizerParams.depth, Double.NaN)
        val boxesNum = getParamsOrElse[Double](DiscretizerParams.boxesNum, Double.NaN)

        require(boxesNum.isNaN ^ depth.isNaN, "等宽分箱中，深度和箱子数需要且只能设置一个。")

        if (!boxesNum.isNaN) {
          require(checkFrameSize(boxesNum.toLong), "箱子数目过多, 导致其边界信息超过了spark.akka.frame.size的上界")

          val newDataFrame = new QuantileDiscretizer()
            .setInputCol(inputCol)
            .setOutputCol(outputCol)
            .setNumBuckets(boxesNum.toInt)
            .fit(data)
            .transform(data)
          new UnaryOutput(newDataFrame)

        } else {
          val dfCount = data.count()
          val boxesNum = scala.math.round(dfCount / depth.toDouble)
          require(boxesNum > 1, s"分箱数至少是2，你设置的深度可能过大，深度不应该超过当前数据总数$dfCount，否则只能分一个箱子")
          require(checkFrameSize(boxesNum), "箱子数目过多, 导致其边界信息超过了spark.akka.frame.size的上界")

          val newDataFrame = new QuantileDiscretizer()
            .setInputCol(inputCol)
            .setOutputCol(outputCol)
            .setNumBuckets(boxesNum.toInt)
            .fit(data)
            .transform(data)
          new UnaryOutput(newDataFrame)
        }

      case "selfDefined" =>
        import org.apache.spark.ml.feature.Bucketizer

        val bucketsAddInfinity = getParamsOrElse[Boolean](DiscretizerParams.bucketsAddInfinity, false)
        val buckets = if (bucketsAddInfinity) {
          Double.MinValue +: getParams[Array[Double]](DiscretizerParams.buckets).sorted :+ Double.MaxValue
        } else {
          getParams[Array[Double]](DiscretizerParams.buckets).sorted :+ Double.MaxValue
        }

        val newDataFrame = new Bucketizer()
          .setInputCol(inputCol)
          .setOutputCol(outputCol)
          .setSplits(buckets)
          .transform(data)

        new UnaryOutput(newDataFrame)
    }

  }
}


class OneHotCoder(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.OneHotEncoder

    val inputCol = getParams[String](OneHotCoderParams.inputCol)
    val outputCol = getParams[String](OneHotCoderParams.outputCol)
    val dropLast = getParamsOrElse[Boolean](OneHotCoderParams.dropLast, false)

    val newDataFrame = new OneHotEncoder()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setDropLast(dropLast)
      .transform(data)

    new UnaryOutput(newDataFrame)

  }
}

class IDFTransformer(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true


  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    /** 2-3 tf-idf转换 */
    import org.apache.spark.ml.feature.{IDF, IDFModel}

    val inputCol = getParams[String](IDFTransformerParams.inputCol)
    val outputCol = getParams[String](IDFTransformerParams.outputCol)
    val loadModel = getParamsOrElse[Boolean](IDFTransformerParams.loadModel, false)

    val idfModel = if (loadModel) {
      val loadPath = getParams[String](IDFTransformerParams.loadPath)
      IDFModel.load(loadPath)
    } else {
      val trainData = getParamsOrElse[DataFrame](IDFTransformerParams.trainData, data)
      val trainInputCol = getParamsOrElse[String](IDFTransformerParams.trainInputCol, inputCol)
      val trainOutputCol = getParamsOrElse[String](IDFTransformerParams.trainOutputCol, outputCol)
      val minDocFreq = getParamsOrElse[Int](IDFTransformerParams.minDocFreq, 0)

      val idf = new IDF()
        .setInputCol(trainInputCol)
        .setOutputCol(trainOutputCol)
        .setMinDocFreq(minDocFreq) // 最低统计词频
      val model = idf.fit(trainData)

      val saveModel = getParamsOrElse[Boolean](IDFTransformerParams.saveModel, false)
      if (saveModel) {
        val savePath = getParams[String](IDFTransformerParams.savePath)
        model.save(savePath)
      }
      model
    }

    val newDataFrame = idfModel.setInputCol(inputCol)
      .setOutputCol(outputCol)
      .transform(data)
    new UnaryOutput(newDataFrame)
  }
}


/** 低变异性数值特征转类别特征 */
class VectorIndexerTransformer(override val data: DataFrame) extends Pretreater[BinaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): BinaryOutput = {
    import org.apache.spark.ml.feature.{VectorIndexer, VectorIndexerModel}

    val inputCol = getParams[String](VectorIndexerParams.inputCol)
    val outputCol = getParams[String](VectorIndexerParams.outputCol)
    val loadModel = getParamsOrElse[Boolean](VectorIndexerParams.loadModel, false)

    val viModel = if (loadModel) {
      val loadPath = getParams[String](VectorIndexerParams.loadPath)
      VectorIndexerModel.load(loadPath)
    } else {
      val trainData = getParamsOrElse[DataFrame](VectorIndexerParams.trainData, data)
      val trainInputCol = getParamsOrElse[String](VectorIndexerParams.trainInputCol, inputCol)
      val trainOutputCol = getParamsOrElse[String](VectorIndexerParams.trainOutputCol, outputCol)

      val maxCategories = getParamsOrElse[Int](VectorIndexerParams.maxCategories, 20)
      val model = new VectorIndexer()
        .setInputCol(trainInputCol)
        .setOutputCol(trainOutputCol)
        .setMaxCategories(maxCategories)
        .fit(trainData)

      val saveModel = getParamsOrElse[Boolean](IDFTransformerParams.saveModel, false)
      if (saveModel) {
        val savePath = getParams[String](IDFTransformerParams.savePath)
        model.save(savePath)
      }
      model
    }

    val categoricalFeatures: Map[Int, Map[Double, Int]] = viModel.setInputCol(inputCol)
      .setOutputCol(outputCol)
      .categoryMaps
    val newDataFrame = viModel.setInputCol(inputCol)
      .setOutputCol(outputCol)
      .transform(data)

    val categoryInfo = new CategoryInfoForVectorIndex(categoricalFeatures)
    new BinaryOutput(newDataFrame, categoryInfo)
  }
}


class PCATransformer(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.{PCA, PCAModel}

    val inputCol = getParams[String](PCAParams.inputCol)
    val outputCol = getParams[String](PCAParams.outputCol)
    val loadModel = getParamsOrElse[Boolean](PCAParams.loadModel, false)

    val pcaModel = if (loadModel) {
      val loadPath = getParams[String](PCAParams.loadPath)
      PCAModel.load(loadPath)
    } else {
      val trainData = getParamsOrElse[DataFrame](PCAParams.trainData, data)
      val trainInputCol = getParamsOrElse[String](PCAParams.trainInputCol, inputCol)
      val trainOutputCol = getParamsOrElse[String](PCAParams.trainOutputCol, outputCol)
      val p = getParams[Int](PCAParams.p)

      val model = new PCA()
        .setInputCol(trainInputCol)
        .setOutputCol(trainOutputCol)
        .setK(p)
        .fit(trainData)

      val saveModel = getParamsOrElse[Boolean](IDFTransformerParams.saveModel, false)
      if (saveModel) {
        val savePath = getParams[String](IDFTransformerParams.savePath)
        model.save(savePath)
      }
      model
    }

    val newDataFrame = pcaModel.setInputCol(inputCol).setOutputCol(outputCol).transform(data)
    new UnaryOutput(newDataFrame)
  }
}


class PlynExpansionTransformer(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.PolynomialExpansion

    val inputCol = getParams[String](PlynExpansionParams.inputCol)
    val outputCol = getParams[String](PlynExpansionParams.outputCol)
    val degree = getParams[Int](PlynExpansionParams.degree)

    val newDataFrame = new PolynomialExpansion()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setDegree(degree)
      .transform(data)

    new UnaryOutput(newDataFrame)
  }
}


class DCTTransformer(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.DCT

    val inputCol = getParams[String](DCTParams.inputCol)
    val outputCol = getParams[String](DCTParams.outputCol)
    val inverse = getParamsOrElse[Boolean](DCTParams.inverse, false)

    val newDataFrame = new DCT()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setInverse(inverse)
      .transform(data)

    new UnaryOutput(newDataFrame)
  }
}


class StringIndexTransformer(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.StringIndexer

    val inputCol = getParams[String](StringIndexParams.inputCol)
    val outputCol = getParams[String](StringIndexParams.outputCol)
    val loadModel = getParamsOrElse[Boolean](StringIndexParams.loadModel, false)

    val stringVectorModel: StringIndexerModel = if (loadModel) {
      val loadPath = getParams[String](StringIndexParams.loadPath)
      StringIndexerModel.load(loadPath)
    } else {
      val trainData = getParamsOrElse[DataFrame](StringIndexParams.trainData, data)
      val trainInputCol = getParamsOrElse[String](StringIndexParams.trainInputCol, inputCol)
      val trainOutputCol = getParamsOrElse[String](StringIndexParams.trainOutputCol, outputCol)

      val model = new StringIndexer()
        .setInputCol(trainInputCol)
        .setOutputCol(trainOutputCol)
        .setHandleInvalid("skip") // Array("skip", "error"))
        .fit(trainData)

      val saveModel = getParamsOrElse[Boolean](StringIndexParams.saveModel, false)
      if (saveModel) {
        val savePath = getParams[String](StringIndexParams.savePath)
        model.save(savePath)
      }
      model
    }
    val handleInvalid = getParamsOrElse[String](StringIndexParams.handleInvalid, "skip")

    import org.apache.spark.sql.functions.col

    val newData = try {
      data.withColumn(inputCol, col(inputCol).cast("double"))
    } catch {
      case e: Exception => throw new Exception(s"您输入的数据列${inputCol}不能转为double类型，${e.getMessage}")
    }

    val newDataFrame = stringVectorModel
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setHandleInvalid(handleInvalid)
      .transform(newData)

    new UnaryOutput(newDataFrame)
  }
}


class IndexerStringTransformer(override val data: DataFrame) extends Pretreater[UnaryOutput](data) {
  override protected def paramsValid(): Boolean = true

  /**
    * [运行函数]接口
    *
    * @return 输出一个PretreatmentOutput的实现子类型
    */
  override protected def runAlgorithm(): UnaryOutput = {
    import org.apache.spark.ml.feature.IndexToString

    val inputCol = getParams[String](IndexToStringParams.inputCol)
    val outputCol = getParams[String](IndexToStringParams.outputCol)
    val loadModel = getParamsOrElse[Boolean](IndexToStringParams.loadModel, false)

    val indexToString: IndexToString = if (loadModel) {
      val loadPath = getParams[String](IndexToStringParams.loadPath)
      IndexToString.load(loadPath)
    } else {
      val trainInputCol = getParamsOrElse[String](IndexToStringParams.trainInputCol, inputCol)
      val trainOutputCol = getParamsOrElse[String](IndexToStringParams.trainOutputCol, outputCol)
      val labels = getParams[Array[String]](IndexToStringParams.labels)

      val model = new IndexToString()
        .setInputCol(trainInputCol)
        .setOutputCol(trainOutputCol)
        .setLabels(labels)


      val saveModel = getParamsOrElse[Boolean](StringIndexParams.saveModel, false)
      if (saveModel) {
        val savePath = getParams[String](StringIndexParams.savePath)
        model.save(savePath)
      }
      model
    }

    val newData = try {
      data.filter(s"$inputCol < ${indexToString.getLabels.length}")
    } catch {
      case e: Exception => throw new Exception(s"数据过滤中失败，" +
        s"请检查${inputCol}是否存在或者类型是否是double类型，${e.getMessage}")
    }

    val newDataFrame = indexToString
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .transform(newData)

    new UnaryOutput(newDataFrame)
  }
}


case class ParamInfo(name: String, annotation: String) // 参数判断写在类外面了

class ParamsName {
  /** 输入列名 */
  val inputCol = ParamInfo("inputCol", "预处理列名")

  /** 输出列名 */
  val outputCol = ParamInfo("outputCol", "输出列名")
}


class ParamsNameFromSavableModel extends ParamsName {
  val loadModel = ParamInfo("loadModel", "是否从持久化引擎中获取词频模型") // 是/否
  /** if [[loadModel]] == 是：需要进一步的训练信息如下 */
  val loadPath = ParamInfo("loadPath", "模型读取路径") // end if

  /** if [[loadModel]] == 否：需要进一步的训练信息如下 */
  val trainData = ParamInfo("trainData", "训练数据")
  val trainInputCol = ParamInfo("trainInputCol", "训练数据列名")
  val trainOutputCol = ParamInfo("trainOutputCol", "训练数据输出列名")
  /** 是否保存新训练的模型 */
  val saveModel = ParamInfo("saveModel", "是否将模型保存到持久化引擎中") // 是/否
  /** if[[saveModel]] == 是 */
  val savePath = ParamInfo("savePath", "模型保存路径") // end if // end if

}


object TokenizerByRegexParamsName extends ParamsName {
  /** 是以此为分隔还是以此为匹配类型 */
  val gaps = ParamInfo("gaps", "是以此为分隔还是以此为匹配类型")

  /** 匹配的模式 */
  val pattern = ParamInfo("pattern", "匹配的模式")

  /** 是否将英文转为小写 */
  val toLowerCase = ParamInfo("toLowerCase", "是否将英文转为小写")

  /** 最小分词长度 --不满足的将会被过滤掉 */
  val minTokenLength = ParamInfo("minTokenLength", "最小分词长度")
}

object CountWordVectorParamsName extends ParamsName {
  val loadModel = ParamInfo("loadModel", "是否从持久化引擎中获取词频模型") // 是/否
  /** if [[loadModel]] == 是：需要进一步的训练信息如下 */
  val loadPath = ParamInfo("loadPath", "模型读取路径") // end if

  /** if [[loadModel]] == 否：需要进一步的训练信息如下 */
  val trainData = ParamInfo("trainData", "训练数据")
  val trainInputCol = ParamInfo("trainInputCol", "训练数据列名")
  val trainOutputCol = ParamInfo("trainOutputCol", "训练数据输出列名")
  val vocabSize = ParamInfo("VocabSize", "词汇数")
  val minDf = ParamInfo("minDf", "最小文档频率")
  val minTf = ParamInfo("minTf", "最小词频")
  /** 是否保存新训练的模型 */
  val saveModel = ParamInfo("saveModel", "是否将模型保存到持久化引擎中") // 是/否
  /** if[[saveModel]] == 是 */
  val savePath = ParamInfo("savePath", "模型保存路径") // end if // end if

}


object HashTFParamsName extends ParamsName {

  val numFeatures = ParamInfo("numFeatures", "词汇数")

}


object WordToVectorParamsName extends ParamsName {
  val loadModel = ParamInfo("loadModel", "是否从持久化引擎中获取词频模型") // 是/否
  /** if [[loadModel]] == 是：需要进一步的训练信息如下 */
  val loadPath = ParamInfo("loadPath", "模型读取路径") // end if

  /** if [[loadModel]] == 否：需要进一步的训练信息如下 */
  val trainData = ParamInfo("trainData", "训练数据")
  val trainInputCol = ParamInfo("trainInputCol", "训练数据列名")
  val trainOutputCol = ParamInfo("trainOutputCol", "训练数据输出列名")
  val vocabSize = ParamInfo("VocabSize", "词汇数")
  val minCount = ParamInfo("minCount", "最小词频")
  val windowSize = ParamInfo("windowSize", "gram宽度")
  val stepSize = ParamInfo("stepSize", "步长")
  val numPartitions = ParamInfo("numPartitions", "并行度")
  val numIterations = ParamInfo("numIterations", "执行次数")
  val seed = ParamInfo("seed", "随机数种子")


  /** 是否保存新训练的模型 */
  val saveModel = ParamInfo("saveModel", "是否将模型保存到持久化引擎中") // 是/否
  /** if[[saveModel]] == 是 */
  val savePath = ParamInfo("savePath", "模型保存路径") // end if // end if

}


object StopWordsRemoverParamsName extends ParamsName {
  val caseSensitive = ParamInfo("caseSensitive", "是否区分大小写")
  val stopWords = ParamInfo("numIterations", "停用词") // 这里严格接受Array[String]类型的停用词，选择英语汉语停用词典放在外层
}


object NGramParamsName extends ParamsName {
  val n = ParamInfo("n", "gram数值")
}


object DiscretizerParams extends ParamsName {
  val discretizeFormat = ParamInfo("discretizeFormat", "离散化模式") // 分为等宽[byWidth]、等深[byDepth]、自定义[selfDefined]

  val phase = ParamInfo("phase", "周期初始相位——某个箱子的起始数值") // if discretizeFormat == byWidth
  val width = ParamInfo("width", "箱子宽度") // if discretizeFormat == byWidth

  val depth = ParamInfo("depth", "深度") // if discretizeFormat == byDepth  --和boxesNum之间二选一
  val boxesNum = ParamInfo("boxesNum", "箱子数") // if discretizeFormat == byDepth

  val buckets = ParamInfo("buckets", "分箱边界") // if discretizeFormat == byDepth
  val bucketsAddInfinity = ParamInfo("bucketsAddInfinity", "是否以极小值作为分箱边界的一部分") // if discretizeFormat == byDepth

}

object OneHotCoderParams extends ParamsName {
  val dropLast = ParamInfo("dropLast", "是否去掉最后一个index")

}

object IDFTransformerParams extends ParamsNameFromSavableModel {
  val minDocFreq = ParamInfo("minDocFreq", "最低文档频率") // 当某个数据出现的文档数（记录数）低于该值时会被过滤掉。
}

object VectorIndexerParams extends ParamsNameFromSavableModel {

  val maxCategories = ParamInfo("maxCategories", "离散型特征频次阈值")
}

object PCAParams extends ParamsNameFromSavableModel {

  val p = ParamInfo("p", "PCA的主成分数")
}

object PlynExpansionParams extends ParamsName {
  val degree = ParamInfo("degree", "展开式的幂")
}


object DCTParams extends ParamsName {
  val inverse = ParamInfo("inverse", "展开式的幂")
}


object StringIndexParams extends ParamsNameFromSavableModel {
  val handleInvalid = ParamInfo("handleInvalid", "怎样处理匹配不上的数据")
}


object IndexToStringParams extends ParamsNameFromSavableModel {
  val labels = ParamInfo("labels", "转换标签")
}








