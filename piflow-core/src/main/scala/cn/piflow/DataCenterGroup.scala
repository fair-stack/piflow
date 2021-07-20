package cn.piflow

import java.lang.Thread.UncaughtExceptionHandler
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.util.{Failure, Success, Try}


class DataCenterGroupImpl extends Group {
  var name = ""
  var uuid = ""
  var parentId = ""
  var edges = ArrayBuffer[Edge]();
  val _mapFlowWithConditions = MMap[String, (GroupEntry, Condition[GroupExecution])]();

  def addGroupEntry(name: String, flow: GroupEntry, con: Condition[GroupExecution] = Condition.AlwaysTrue[GroupExecution]) = {
    _mapFlowWithConditions(name) = flow -> con;
  }

  def mapFlowWithConditions(): Map[String, (GroupEntry, Condition[GroupExecution])] = _mapFlowWithConditions.toMap;


  def addPath(path: Path): Group = {
    edges ++= path.toEdges();
    this;
  }


  override def getGroupName(): String = {
    this.name
  }

  override def setGroupName(groupName: String): Unit = {
    this.name = groupName
  }

  override def getParentGroupId(): String = { "" }


  override def setParentGroupId(groupId: String): Unit = {}

  override def getEdges(): ArrayBuffer[Edge] = {
    this.edges
  }
}

class DataCenterGroupExecutionImpl(group: Group, runnerContext: Context, runner: Runner) extends GroupExecution {

  val groupContext = createContext(runnerContext);
  val groupExecution = this;

  val id : String = "group_" + UUID.randomUUID().toString;

  val mapGroupEntryWithConditions: Map[String, (GroupEntry, Condition[GroupExecution])] = group.mapFlowWithConditions();

  //completed flow
  val completedGroupEntry = MMap[String, Boolean]();
  completedGroupEntry ++= mapGroupEntryWithConditions.map(x => (x._1, false))

  // waiting flow number
  val numWaitingGroupEntry = new AtomicInteger(mapGroupEntryWithConditions.size)


  //init imcomingEdges and outgoingEdges
  val incomingEdges = MMap[String, ArrayBuffer[Edge]]();
  val outgoingEdges = MMap[String, ArrayBuffer[Edge]]();
  group.getEdges().foreach { edge =>
    incomingEdges.getOrElseUpdate(edge.stopTo, ArrayBuffer[Edge]()) += edge;//use this
    outgoingEdges.getOrElseUpdate(edge.stopFrom, ArrayBuffer[Edge]()) += edge;
  }

  //init dataCenter info
  val dataCenterMap = MMap[String, String]()
  mapGroupEntryWithConditions.foreach { en => dataCenterMap(en._1) = en._2._1.asInstanceOf[Flow].getDataCenter() }

  //started flow appId Map
  val startedProcessesAppID = MMap[String, String]()

  val execution = this;
  val POLLING_INTERVAL = 1000;
  val latch = new CountDownLatch(1);
  var running = true;

  val listener = new RunnerListener {

    override def onProcessStarted(ctx: ProcessContext): Unit = {}

    override def onProcessFailed(ctx: ProcessContext): Unit = {
      //TODO: retry?
    }
    override def onProcessCompleted(ctx: ProcessContext): Unit = {

    }
    override def onProcessForked(ctx: ProcessContext, child: ProcessContext): Unit = {}

    override def onProcessAborted(ctx: ProcessContext): Unit = {}

    override def onGroupStarted(ctx: GroupContext): Unit = {}

    //TODO:!!!
    override def onGroupCompleted(ctx: GroupContext): Unit = {
      /*startedGroup.filter(_._2 == ctx.getGroupExecution()).foreach { x =>
        completedGroupEntry(x._1) = true;
        numWaitingGroupEntry.decrementAndGet();
      }*/
    }

    override def onGroupStoped(ctx: GroupContext): Unit = {}

    override def onGroupFailed(ctx: GroupContext): Unit = {}

    override def onJobInitialized(ctx: JobContext): Unit = {}

    override def onJobStarted(ctx: JobContext): Unit = {}

    override def onJobCompleted(ctx: JobContext): Unit = {}

    override def onJobFailed(ctx: JobContext): Unit = {}

    override def monitorJobCompleted(ctx: JobContext, outputs: JobOutputStream): Unit = {}
  };

  runner.addListener(listener);
  val runnerListener = runner.getListener()


  def isEntryCompleted(name: String): Boolean = {
    completedGroupEntry(name)
  }

  private def startProcess(name: String, flow: Flow, groupId: String = ""): Unit = {

    var flowJson = flow.getFlowJson()

    //TODO: replace flow json
    val flowIncomingEdge  = incomingEdges(name)
    flowIncomingEdge.foreach(edge => {

      val fromFlow = edge.stopFrom
      val fromFlowDataCenter = dataCenterMap(fromFlow)
      val fromFlowAppID = startedProcessesAppID(fromFlow)// check exist
      val fromOutport = edge.outport
      //TODO: send request to get data source from remote datacenter
      //val dataSource = getDataCenterData(fromFlowDataCenter, fromFlowAppID, fromOutport)
      //replace flow Json
    })
    //var flowJsonNew = constructDataCenterFlowJson()
    //TODO: send request to run flow!!!!!!!!! zhoujianpeng
    //construct new flow json
    var appId : String = ""
    startedProcessesAppID(name) = appId
    //TODO: save flow status by H2Util
  }

  @volatile
  var maybeException:Option[Throwable] = None
  val pollingThread = new Thread(new Runnable() {
    override def run(): Unit = {

      runnerListener.onGroupStarted(groupContext)

      try{
        while (numWaitingGroupEntry.get() > 0) {

          val todosFlow = getTodos()

          startedProcessesAppID.synchronized {
            todosFlow.foreach(en => {
              startProcess(en._1, en._2.asInstanceOf[Flow],id)
            });
          }

          Thread.sleep(POLLING_INTERVAL);
          //TODO: check wether flow finished!!!!!!!
          completedGroupEntry.filter(x => x._2 == false).map(_._1).foreach{flowName => {
            val appId = startedProcessesAppID(flowName)
            //TODO: sent request, getFlowInfo(dataCenter,appId)
            //TODO:update flow status
            var finishedFlowName = ""
            if(true/*flowInfo.status == Finished*/){
              completedGroupEntry(finishedFlowName) = true
              numWaitingGroupEntry.decrementAndGet
            }
          }}
        }

        runnerListener.onGroupCompleted(groupContext)

      }catch {
        case e: Throwable =>
          runnerListener.onGroupFailed(groupContext);
          println(e)
          if(e.isInstanceOf[GroupException])
            throw e
      }
      finally {
        latch.countDown();
        finalizeExecution(true);
      }
    }
  });

  val doit = Try{
    pollingThread.setUncaughtExceptionHandler( new UncaughtExceptionHandler {
      override def uncaughtException(thread: Thread, throwable: Throwable): Unit = {
        maybeException = Some(throwable)
      }
    })
    pollingThread.start()
    //pollingThread.join()
  }

  doit match {
    case Success(v) => {
      println("Did not capture error!")
    }
    case Failure(v) =>{
      println("Capture error!")
      runnerListener.onGroupFailed(groupContext)
    }

  }

  override def awaitTermination(): Unit = {
    latch.await();
    finalizeExecution(true);
  }

  override def stop(): Unit = {
    finalizeExecution(false);
    //runnerListener.onProjectStoped(projectContext)
  }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Unit = {
    if (!latch.await(timeout, unit))
      finalizeExecution(false);
  }

  private def finalizeExecution(completed: Boolean): Unit = {
    if (running) {
      if (!completed) {

        startedProcessesAppID.synchronized{
          startedProcessesAppID.filter(x => !isEntryCompleted(x._1)).foreach(x => {

            //x._2.stop()
            val appID: String = startedProcessesAppID.getOrElse(x._1,"")
            if(!appID.equals("")){

              //TODO:set request to remote DataCenter!!!!! zhoujianpeng
              println("Stop Flow " + appID + " by Request!")
            }

          });
        }

        pollingThread.interrupt();

      }

      runner.removeListener(listener);
      running = false;
    }
  }

  private def createContext(runnerContext: Context): GroupContext = {
    new CascadeContext(runnerContext) with GroupContext {
      override def getGroup(): Group = group

      override def getGroupExecution(): GroupExecution = groupExecution

    };
  }

  private def getTodos() : (ArrayBuffer[(String, Flow)]) = {

    val todosFlow = ArrayBuffer[(String, Flow)]();
    mapGroupEntryWithConditions.foreach { en =>

      if (!startedProcessesAppID.contains(en._1) && en._2._2.matches(execution)) {
        todosFlow += (en._1 -> en._2._1.asInstanceOf[Flow]);
      }
    }
    todosFlow
  }

  override def getGroupId(): String = id

  override def getChildCount(): Int = {
    mapGroupEntryWithConditions.size

  }
}

