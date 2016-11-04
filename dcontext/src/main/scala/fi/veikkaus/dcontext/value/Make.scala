package fi.veikkaus.dcontext.value

import java.io.File

import fi.veikkaus.dcontext.store.Store

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * MakeVal establishes a simple build system, that is used for tracking various
  * values, that are factoring from mutating sources.
  */
class Make[Value, Source, Version](source : Versioned[Source, Version],
                                  f : Source=>Value,
                                  valueStore:Store[Try[Value]],
                                  versionStore:Store[Version])
  extends VersionedVal[Value, Version] {

  private var request : Option[(Option[Version], Future[Option[(Try[Value], Version)]])] = None

  //
  // An overly complex method because a request barrier.
  //
  // The idea is that we may get lot of requests with different version numbers.
  // We make one request with the first version number and we end with 3 special cases:
  //
  // 1. First, the first request may return (value, newestVersion) pair,
  //
  //    Here we need to compare all requests' version numbers to the newest version
  //      if it is the same version, let's return None
  //      if it is different version, we need to return (value, newestVersion) pair
  //
  // 2. Second the first request may return None, it means that first request version is newest
  //
  //    Here we need to compare all requests' version numbers to this newest version
  //      if it is the same version, let's return None
  //      if it is not, we need to do another request with the older version number
  //
  override def updated(version: Option[Version]): Future[Option[(Try[Value], Version)]] = synchronized {
    val doUpdate = () => {
      val rv =
        source.updated(version).map {
          _.map { case (source, newestVersion) =>
            Make.this.synchronized {
              val value = versionStore.get match {
                case Some(storedVersion) if storedVersion == newestVersion =>
                  valueStore.get.get
                case _ =>
                  val v = source.map(f(_))
                  valueStore.update(Some(v))
                  versionStore.update(Some(newestVersion))
                  v
              }
              (value, newestVersion): (Try[Value], Version)
            }
          }
        }
      rv.onComplete { res =>
        Make.this.synchronized {
          request = None
        }
      }
      rv
    }
    request match {
      case None =>
        val f = doUpdate()
        request = Some((version, f))
        f
      case Some((v, f)) =>
        f.flatMap { _ match {
          case Some((value, newestV)) =>
            Future {
              if (version == Some(newestV)) {
                None
              } else {
                Some((value, newestV))
              }
            }
          case None =>
            if (version == v) { // v is the newest version, and this request has it, good
              Future { None }
            } else { // oh no, v is the newest version, but this request doesn't have it -> rerequest
              updated(version) // redo the request
            }
          }
        }
    }
  }
  def valueAndVersion : Future[(Try[Value], Version)] = {
    updated(versionStore.get).map(
      _.getOrElse(
        Make.this.synchronized (valueStore.get.get, versionStore.get.get)))
  }
  def storedVersion = versionStore.get
  def get = valueAndVersion.map(_._1.get)
}
/*
class MakeFile[Source, Version](source : Versioned[Source, Version],
                                f : (Source, File)=>Unit,
                                file:File,
                                versionStore:Store[Version])
  extends Versioned[File, Version] with AsyncVal[File] {
  // returns an updated version, if one exists
  override def updated(version: Option[Version]): Future[Option[(Try[File], Version)]] = {
    source.updated(version).map { _.map { case (source, version) =>
      MakeFile.this.synchronized {
        val value = source.map { s => f(s, file); file  }
        versionStore.update(Some(version))
        (value, version)
      }
    }}
  }
  def valueAndVersion : Future[(Try[File], Version)] = {
    updated(versionStore.get).map(
      _.getOrElse(
        MakeFile.this.synchronized (.get.get, versionStore.get.get)))
  }
  def storedVersion = versionStore.get
  def get = valueAndVersion.map(_._1.get)
}
*/

object Make {

  def apply[Type, Source, Version](
      valueStore:Store[Try[Type]], versionStore:Store[Version])(
      s1:Versioned[Source, Version])(f : Source => Type) = {
    new Make[Type, Source, Version](s1, f, valueStore, versionStore)
  }

  def apply[Type, Source1, Version1, Source2, Version2](
     valueStore:Store[Try[Type]], versionStore:Store[(Version1, Version2)])(
     s1:Versioned[Source1, Version1], s2:Versioned[Source2, Version2])(f : ((Source1, Source2)) => Type) = {
    new Make[Type, (Source1, Source2), (Version1, Version2)](
      new VersionedPair[Source1, Version1, Source2, Version2](s1, s2),
      f,
      valueStore,
      versionStore)
  }

}