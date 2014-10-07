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
package io

import java.io.File
import org.specs2.specification.Scope
import org.apache.hadoop.fs.{FileSystem, FileStatus, Path}
import impl.ScoobiConfiguration
import com.nicta.scoobi.impl.io.{Files, FileSystems}
import testing.mutable.UnitSpecification
import org.specs2.mutable.Tables
import java.net.URI
import org.apache.hadoop.conf.Configuration

class FileSystemsSpec extends UnitSpecification with Tables {
  "A local file can be compared to a list of files on the server to check if it is outdated" >> {
    implicit val sc = ScoobiConfiguration()

    "if it has the same name and same size, it is an old file" >> new fs {
      isOldFile(Seq(new Path("uploaded"))).apply(new File("uploaded")) must beTrue
    }
    "if it has the same name and not the same size, it is a new  file" >> new fs {
      uploadedLengthIs = 10

      isOldFile(Seq(new Path("uploaded"))).apply(new File("uploaded")) must beFalse
    }
    "otherwise it is a new file" >> new fs {
      isOldFile(Seq(new Path("uploaded"))).apply(new File("new")) must beFalse
    }
  }
  "2 file systems are the same if they have the same host and same scheme" >> {
    val nullString: String = null
    def uri(scheme: String, host: String) =
      if (host == null) FileSystem.get(new URI(scheme+":"), new Configuration)
      else              FileSystem.get(new URI(scheme+"://"+host+":3100/"), new Configuration)

    "scheme1" | "host1"     | "scheme1" | "host2"      | "same?" |>
    "file"    ! "localhost" ! "file"    ! "local"      ! true    |
    "hdfs"    ! "localhost" ! "file"    ! "local"      ! false   |
    "hdfs"    ! "localhost" ! "hdfs"    ! "google.com" ! false   |
    "hdfs"    ! "localhost" ! "file"    ! "google.com" ! false   | { (s1, h1, s2, h2, same) =>
      Files.sameFileSystem(uri(s1, h1), uri(s2, h2)) === same
    }
  }

  trait fs extends FileSystems with Scope {
    var uploadedLengthIs = 0
    /** default file status for all test cases */
    override def fileStatus(path: Path)(implicit configuration: Configuration) =
      new FileStatus(uploadedLengthIs, false, 0, 0, 0, 0, null, null, null, null)
  }
}
