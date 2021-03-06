/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;

import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.DoFnRunner.ReduceFnExecutor;
import com.google.cloud.dataflow.sdk.util.TimerInternals.TimerData;
import com.google.cloud.dataflow.sdk.util.state.StateInternals;
import com.google.cloud.dataflow.sdk.values.KV;

/**
 * A general {@link GroupAlsoByWindowsDoFn}. This delegates all of the logic to the
 * {@link ReduceFnRunner}.
 */
@SystemDoFnInternal
public class GroupAlsoByWindowViaWindowSetDoFn<
        K, InputT, OutputT, W extends BoundedWindow, RinT extends KeyedWorkItem<K, InputT>>
    extends DoFn<RinT, KV<K, OutputT>> implements ReduceFnExecutor<K, InputT, OutputT, W> {

  public static <K, InputT, OutputT, W extends BoundedWindow>
      DoFn<KeyedWorkItem<K, InputT>, KV<K, OutputT>> create(
          WindowingStrategy<?, W> strategy, SystemReduceFn<K, InputT, ?, OutputT, W> reduceFn) {
    return new GroupAlsoByWindowViaWindowSetDoFn<>(strategy, reduceFn);
  }

  protected final Aggregator<Long, Long> droppedDueToClosedWindow =
      createAggregator(
          GroupAlsoByWindowsDoFn.DROPPED_DUE_TO_CLOSED_WINDOW_COUNTER, new Sum.SumLongFn());
  protected final Aggregator<Long, Long> droppedDueToLateness =
      createAggregator(GroupAlsoByWindowsDoFn.DROPPED_DUE_TO_LATENESS_COUNTER, new Sum.SumLongFn());

  private final WindowingStrategy<Object, W> windowingStrategy;
  private SystemReduceFn<K, InputT, ?, OutputT, W> reduceFn;

  private GroupAlsoByWindowViaWindowSetDoFn(
      WindowingStrategy<?, W> windowingStrategy,
      SystemReduceFn<K, InputT, ?, OutputT, W> reduceFn) {
    @SuppressWarnings("unchecked")
    WindowingStrategy<Object, W> noWildcard = (WindowingStrategy<Object, W>) windowingStrategy;
    this.windowingStrategy = noWildcard;
    this.reduceFn = reduceFn;
  }

  @Override
  public void processElement(ProcessContext c) throws Exception {
    KeyedWorkItem<K, InputT> element = c.element();

    K key = c.element().key();
    TimerInternals timerInternals = c.windowingInternals().timerInternals();

    // It is the responsibility of the user of GroupAlsoByWindowsViaWindowSet to only
    // provide a WindowingInternals instance with the appropriate key type for StateInternals.
    @SuppressWarnings("unchecked")
    StateInternals<K> stateInternals = (StateInternals<K>) c.windowingInternals().stateInternals();

    ReduceFnRunner<K, InputT, OutputT, W> reduceFnRunner =
        new ReduceFnRunner<>(
            key,
            windowingStrategy,
            stateInternals,
            timerInternals,
            c.windowingInternals(),
            droppedDueToClosedWindow,
            reduceFn,
            c.getPipelineOptions());

    reduceFnRunner.processElements(element.elementsIterable());
    for (TimerData timer : element.timersIterable()) {
      reduceFnRunner.onTimer(timer);
    }
    reduceFnRunner.persist();
  }

  @Override
  public DoFn<KeyedWorkItem<K, InputT>, KV<K, OutputT>> asDoFn() {
    // Safe contravariant cast
    @SuppressWarnings("unchecked")
    DoFn<KeyedWorkItem<K, InputT>, KV<K, OutputT>> asFn =
        (DoFn<KeyedWorkItem<K, InputT>, KV<K, OutputT>>) this;
    return asFn;
  }

  @Override
  public Aggregator<Long, Long> getDroppedDueToLatenessAggregator() {
    return droppedDueToLateness;
  }
}

