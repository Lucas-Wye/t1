// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>

package org.chipsalliance.t1.rtl.lane

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.{LaneExecuteStage, LaneParameter}
import org.chipsalliance.t1.rtl.decoder.Decoder

class LaneStage2Enqueue(parameter: LaneParameter, isLastSlot: Boolean) extends Bundle {
  val src: Vec[UInt] = Vec(3, UInt(parameter.datapathWidth.W))
  val groupCounter: UInt = UInt(parameter.groupNumberBits.W)
  val maskForFilter: UInt = UInt((parameter.datapathWidth / 8).W)
  val mask: UInt = UInt((parameter.datapathWidth / 8).W)
  val sSendResponse: Option[Bool] = Option.when(isLastSlot)(Bool())
}

class LaneStage2Dequeue(parameter: LaneParameter, isLastSlot: Boolean) extends Bundle {
  val groupCounter: UInt = UInt(parameter.groupNumberBits.W)
  val mask: UInt = UInt((parameter.datapathWidth / 8).W)
  val sSendResponse: Option[Bool] = Option.when(isLastSlot)(Bool())
  val pipeData: Option[UInt] = Option.when(isLastSlot)(UInt(parameter.datapathWidth.W))
}

// s2 执行
class LaneStage2(parameter: LaneParameter, isLastSlot: Boolean) extends
  LaneStage(true)(
    new LaneStage2Enqueue(parameter, isLastSlot),
    new LaneStage2Dequeue(parameter, isLastSlot)
  ){
  val state: LaneState = IO(Input(new LaneState(parameter)))

  val decodeResult: DecodeBundle = state.decodeResult

  val executionQueue: Queue[LaneExecuteStage] =
    Module(new Queue(new LaneExecuteStage(parameter)(isLastSlot), parameter.executionQueueSize))

  // ffo success in current data group?
  val ffoSuccess: Option[Bool] = Option.when(isLastSlot)(RegInit(false.B))

  val ffoCompleteWrite: UInt = Mux(state.maskType, (~enqueue.bits.src(0)).asUInt & enqueue.bits.src(2), 0.U)
  // executionQueue enqueue
  executionQueue.io.enq.bits.pipeData.foreach { data =>
    data := Mux(
      // pipe source1 for gather, pipe ~v0 & vd for ffo
      decodeResult(Decoder.gather) || decodeResult(Decoder.ffo),
      Mux(decodeResult(Decoder.gather), enqueue.bits.src(0), ffoCompleteWrite),
      enqueue.bits.src(1)
    )
  }
  executionQueue.io.enq.bits.sSendResponse.foreach {d => d := enqueue.bits.sSendResponse.get}
  executionQueue.io.enq.bits.groupCounter := enqueue.bits.groupCounter
  executionQueue.io.enq.bits.mask := Mux1H(
    state.vSew1H,
    Seq(
      enqueue.bits.maskForFilter,
      FillInterleaved(2, enqueue.bits.maskForFilter(1, 0)),
      // todo: handle first masked
      FillInterleaved(4, enqueue.bits.maskForFilter(0))
    )
  )
  executionQueue.io.enq.valid := enqueue.valid
  enqueue.ready := executionQueue.io.enq.ready
  dequeue.valid := executionQueue.io.deq.valid
  executionQueue.io.deq.ready := dequeue.ready

  dequeue.bits.pipeData.foreach(_ := executionQueue.io.deq.bits.pipeData.get)
  dequeue.bits.groupCounter := executionQueue.io.deq.bits.groupCounter
  dequeue.bits.mask := executionQueue.io.deq.bits.mask
  dequeue.bits.sSendResponse.foreach(_ := executionQueue.io.deq.bits.sSendResponse.get)
  stageValid := executionQueue.io.deq.valid
}
