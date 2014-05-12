/**
 * Copyright 2011,2012 National ICT Australia Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nicta.scoobi
package impl
package mapreducer

import org.apache.commons.logging.LogFactory
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.FileStatus
import org.apache.hadoop.io.{Writable, NullWritable, SequenceFile}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat
import org.apache.hadoop.mapreduce.Job

import core._
import rtt._
import com.nicta.scoobi.impl.io.{GlobIterator, Files}
import ScoobiConfiguration._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.io.SequenceFile.CompressionType
import util.Compatibility
import org.apache.hadoop.util.ReflectionUtils

/** A bridge store is any data that moves between MSCRs. It must first be computed, but
  * may be removed once all successor MSCRs have consumed it. */
case class BridgeStore[A](bridgeStoreId: String, wf: WireReaderWriter, checkpoint: Option[Checkpoint] = None, compression: Option[Compression] = None, pattern: String = BridgeStore.pattern) extends
   DataSource[NullWritable, ScoobiWritable[A], A] with
   DataSink[NullWritable, ScoobiWritable[A], A]   with
   Bridge {

  override val id: Int = Data.ids.get
  override lazy val stringId = bridgeStoreId

  lazy val logger = LogFactory.getLog("scoobi.Bridge")

  /** rtClass will be created at runtime as part of building the MapReduce job. */
  def rtClass(implicit sc: ScoobiConfiguration): RuntimeClass =
    BridgeStore.runtimeClasses.getOrElseUpdate(typeName, ScoobiWritable(typeName, wf)(sc))

  /** type of the generated class for this Bridge */
  lazy val typeName = "BS" + checkpoint.map(c => scala.util.hashing.MurmurHash3.stringHash(c.path.toUri.toString)).getOrElse(bridgeStoreId)

  def path(implicit sc: ScoobiConfiguration) = checkpoint.map(_.path).getOrElse(new Path(sc.workingDirectory, "bridges/" + bridgeStoreId))

  /* Output (i.e. input to bridge) */
  def outputFormat(implicit sc: ScoobiConfiguration) = classOf[SequenceFileOutputFormat[NullWritable, ScoobiWritable[A]]]
  def outputKeyClass(implicit sc: ScoobiConfiguration) = classOf[NullWritable]
  def outputValueClass(implicit sc: ScoobiConfiguration) = rtClass(sc).clazz.asInstanceOf[Class[ScoobiWritable[A]]]
  def outputCheck(implicit sc: ScoobiConfiguration) {}
  def outputConfigure(job: Job)(implicit sc: ScoobiConfiguration) {}
  def outputPath(implicit sc: ScoobiConfiguration) = Some(path)

  lazy val outputConverter = new ScoobiWritableOutputConverter[A](typeName)

  /* Input (i.e. output of bridge) */
  lazy val inputFormat = classOf[SequenceFileInputFormat[NullWritable, ScoobiWritable[A]]]
  def inputCheck(implicit sc: ScoobiConfiguration) {}
  def inputConfigure(job: Job)(implicit sc: ScoobiConfiguration) {
     FileInputFormat.addInputPath(job, new Path(path(sc), pattern))
  }

  def inputSize(implicit sc: ScoobiConfiguration): Long = Files.pathSize(new Path(path, pattern))(sc)

  lazy val inputConverter = new InputConverter[NullWritable, ScoobiWritable[A], A] {
    def fromKeyValue(context: InputContext, key: NullWritable, value: ScoobiWritable[A]): A = value.get
  }


  /* Free up the disk space being taken up by this intermediate data. */
  def freePath(implicit sc: ScoobiConfiguration) {
    val fs = path.getFileSystem(sc)
    fs.delete(path, true)
  }

  /**
   * Read the contents of this bridge store sequence files as an Iterable collection. The
   * underlying Iterator has a lazy implementation and will only bring one element into memory
   * at a time
   */
  def readAsIterable(implicit sc: ScoobiConfiguration): Iterable[A] = new Iterable[A] {
    /** instantiate a ScoobiWritable from the Writable class generated for this BridgeStore */
    lazy val value: ScoobiWritable[A] =
      rtClass(sc).clazz.newInstance.asInstanceOf[ScoobiWritable[A]]

    def iterator = new BridgeStoreIterator[A](value, path, sc, pattern)
  }

  override def toString = typeName+"("+id+")"

  override def equals(other: Any) = {
    other match {
      case bs: BridgeStore[_] => bs.bridgeStoreId == this.bridgeStoreId
      case _                  => false
    }
  }

  override def hashCode = bridgeStoreId.hashCode

  def compressWith(codec: CompressionCodec, compressionType: CompressionType = CompressionType.BLOCK) = copy(compression = Some(Compression(codec, compressionType)))

  def toSource: Source = this
}

object BridgeStore {
  /** runtime class for bridgestores, they shouldn't be recreated after it's been created once */
  val runtimeClasses: scala.collection.mutable.Map[String, RuntimeClass] = new scala.collection.mutable.HashMap()
  val pattern = s"${ChannelOutputFormat.basename}*"
}

class BridgeStoreIterator[A](value: ScoobiWritable[A], path: Path, sc: ScoobiConfiguration, pattern: String = BridgeStore.pattern) extends Iterator[A] {
  private lazy val iterator = new GlobIterator[A](new Path(path, pattern), GlobIterator.scoobiWritableIterator(value)(sc.configuration))(sc.configuration)
  def next() = iterator.next
  def hasNext = iterator.hasNext
  def close = iterator.close
}

/** OutputConverter for a bridges. The expectation is that by the time toKeyValue is called,
  * the Class for 'value' will exist and be known by the ClassLoader. */
class ScoobiWritableOutputConverter[A](typeName: String) extends OutputConverter[NullWritable, ScoobiWritable[A], A] {
  private var value: ScoobiWritable[A] = _
  def toKeyValue(x: A)(implicit configuration: Configuration): (NullWritable, ScoobiWritable[A]) = {
    if (value == null) {
      value = ReflectionUtils.newInstance(configuration.getClassLoader.loadClass(typeName), configuration).asInstanceOf[ScoobiWritable[A]]
    }
    value.set(x)
    (NullWritable.get, value)
  }
}
