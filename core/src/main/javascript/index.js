// @flow

import React from "react";

import createHistory from "history/createBrowserHistory"; // choose a history implementation
import { createStore, compose, applyMiddleware } from "redux";
import { Provider } from "react-redux";
import { createRouter, navigate } from "redux-url";
import { render } from "react-dom";
import ReduxThunk from "redux-thunk";

import "../style/index.less";

import App from "./App";
import { initialState, reducers } from "./state";
import * as Actions from "./actions";
import type { Statistics } from "./datamodel";
import { listenEvents } from "./Utils";

import { openPage } from "./actions";

const routes = {
  "/": () => openPage({ id: "executions/started" }),
  "/executions/started": (_, { page, sort, order }) =>
    openPage({ id: "executions/started", page, sort, order }),
  "/executions/stuck": (_, { page, sort, order }) =>
    openPage({ id: "executions/stuck", page, sort, order }),
  "/executions/finished": (_, { page, sort, order }) =>
    openPage({ id: "executions/finished", page, sort, order }),
  "/executions/paused": (_, { page, sort, order }) =>
    openPage({ id: "executions/paused", page, sort, order }),
  "/executions/:id": ({ id }) =>
    openPage({ id: "executions/detail", execution: id }),
  "/workflow": () => openPage({ id: "workflow" }),
  "/timeseries/calendar": () => openPage({ id: "timeseries/calendar" }),
  "/timeseries/backfills": () => openPage({ id: "timeseries/backfills" })
};

const router = createRouter(routes, createHistory());
const store = createStore(
  reducers,
  initialState,
  compose(
    applyMiddleware(router, ReduxThunk),
    window.devToolsExtension ? window.devToolsExtension() : _ => _
  )
);

router.sync();
store.dispatch(Actions.loadAppData());
listenEvents("/api/statistics?events=true", stats =>
  store.dispatch(Actions.updateStatistics(stats))
);

render(
  <Provider store={store}>
    <App />
  </Provider>,
  document.getElementById("app")
);
