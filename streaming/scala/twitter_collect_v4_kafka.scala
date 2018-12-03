/**
 * Sources of inspiration
 * https://github.com/retroryan/twitter_classifier/blob/master/scala/src/main/scala/com/databricks/apps/twitter_classifier/Collect.scala
 * https://databricks.gitbooks.io/databricks-spark-reference-applications/content/twitter_classifier/collect.html
 */

import org.apache.log4j.{Level, Logger}

import org.apache.spark.streaming.{Seconds, Minutes, StreamingContext}
import org.apache.spark.streaming.twitter._
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.util.IntParam

import java.io.File
import scala.io.Source

object TwitterSelectedTags {

  def main(args: Array[String]) {

    // this controls a lot of spark related logging
    // comment or change logging level as needed
    Logger.getLogger("org").setLevel(Level.OFF);
    Logger.getLogger("akka").setLevel(Level.OFF);

    // Process program arguments and set properties
    if (args.length != 8) {
      System.err.println("USER INPUT ERROR")
      System.exit(1)
    }

    val intervalSeconds = args(0).toInt
    val vocab = Source.fromFile(args(1)).getLines.toSet

    System.setProperty("twitter4j.oauth.consumerKey", args(3))
    System.setProperty("twitter4j.oauth.consumerSecret", args(4))
    System.setProperty("twitter4j.oauth.accessToken", args(5))
    System.setProperty("twitter4j.oauth.accessTokenSecret", args(6))

    var numTweetsCollected1 = 0L
    var numTweetsCollected2 = 0L

    println("Initializing Streaming Spark Context...")
    val conf = new SparkConf().setAppName(this.getClass.getSimpleName)
    val sc = new SparkContext(conf)
    val ssc = new StreamingContext(sc, Minutes(intervalSeconds))

    val tweetStream = TwitterUtils.createStream(ssc, None)

    val partitionsEachInterval = sc.broadcast(args(7).toInt)

    val vocab_bc = sc.broadcast(vocab)

    def returnStatus (status: twitter4j.Status) : twitter4j.Status = {
      if (status.isRetweet){
          return status.getRetweetedStatus
        } else {
          return status
        }
      }

    def returnPlace (status: twitter4j.Status) : (String, String) = {
      if (status.getPlace!=null){
          return (status.getPlace.getCountry, status.getPlace.getFullName.replaceAll(","," "))
        } else {
          return ("","")
        }
      }

    def checkVocab (status: String) : Long = {
      val words : Array[String] = status.split(" ")
      var count_words = 0L
      for( word <- words ){
        val clean_word : String = word.replaceAll("[^a-zA-Z0-9] ","").toLowerCase()
        if (vocab_bc.value.contains(clean_word)){
          count_words += 1
        }
      }
      return count_words
    }

    val kafkaProps = new Properties()
    kafkaProps.put("bootstrap.servers", "kafka.rasbonics.com:29092")
    kafkaProps.put("acks", "1")
    kafkaProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    kafkaProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

    def sendToKafka (topic: String, tweetData: TweetData) {
      val kafkaProducer = new KafkaProducer[String, String](kafkaProps)
      val record = new ProducerRecord[String, String](topic, tweetData.toString())
      kafkaProducer.send(record)
      kafkaProducer.close()
    }

    tweetStream.foreachRDD((rdd, time) => {
      println("New batch incoming...")
      val count_ttl = sc.broadcast(rdd.count())
      println("Number of tweets in the batch: %s".format(count_ttl.value))
      if (count_ttl.value > 0) {

        val tweetRDD = rdd.filter(status => status.getLang == "en" && !status.isRetweet)
                          .filter(status => checkVocab(status.getText) > 0)
                          .map(status => (status.getId,
                                         status.getUser.getScreenName,
                                         status.getCreatedAt,
                                         status.getText.replaceAll(",","").split('\n').map(_.trim.filter(_ >= ' ')).mkString,
                                         status.isTruncated,
                                         status.isRetweet,
                                         status.getRetweetCount,
                                         status.getFavoriteCount,
                                         status.isPossiblySensitive,
                                         returnPlace(status),
                                         checkVocab(status.getText)))

        val retweetRDD = rdd.filter(status => status.getLang == "en" && status.isRetweet)
                            .map(returnStatus)
                            .filter(status => checkVocab(status.getText) > 0)
                            .map(status => (status.getId,
                                           status.getUser.getScreenName,
                                           status.getCreatedAt,
                                           status.getText.replaceAll(",","").split('\n').map(_.trim.filter(_ >= ' ')).mkString,
                                           status.isTruncated,
                                           status.isRetweet,
                                           status.getRetweetCount,
                                           status.getFavoriteCount,
                                           status.isPossiblySensitive,
                                           returnPlace(status),
                                           checkVocab(status.getText)))

        tweetRDD.foreach(tweet => sendToKafka("tweets-vocab", tweet))
        retweetRDD.foreach(tweet => sendToKafka("retweets-vocab", tweet))

        val count1 = sc.broadcast(tweetRDD.count())
        val count2 = sc.broadcast(retweetRDD.count())

        numTweetsCollected1 += count1
        numTweetsCollected2 += count2

        println("Number of vocab matching tweets: %s".format(count1))
        println("Number of vocab matching retweets: %s".format(count2))
        println("Total number of vocab tweets collected: %s".format(numTweetsCollected1))
        println("Total number of vocab retweets collected: %s".format(numTweetsCollected2))
      }
    })

    ssc.start()
    ssc.awaitTermination()
  }
}
