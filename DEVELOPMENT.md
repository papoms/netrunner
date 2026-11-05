## Playing on jinteki.net with this client (3D board)

This branch (`3d-on-v171`) is the 3D board on top of v171, the version
jinteki.net runs (its page loads `/js/main.js?v=v171`). The idea: jinteki.net
stays the server; only the client code comes from your machine.

1. Build once (and after every change): `bin/build-3d-client`
   (release build into `resources/release-3d/`, in a throwaway container).
2. Serve it: `bin/serve-3d-client` (http://localhost:8000; keep it running).
3. Browser extension [Requestly](https://requestly.com) (Firefox or Chrome),
   one Redirect rule:
   - Request URL, *Matches (RegEx)*:
     `^https://jinteki\.net/(js/(main|board3d)\.js|css/netrunner\.css)`
   - Redirect to: `http://localhost:8000/$1`
4. Open https://jinteki.net, Settings → "Experimental 3D version": tick
   "3D game board" (and optionally full width / experimental interface), Update
   Profile. For a playmat use a full URL such as
   `http://localhost:8000/img/playmats/<file>.jpg`.

Switch the Requestly rule off to get the regular client back. When jinteki.net
moves to a new version (check the `?v=` on main.js), the port has to be redone
on that tag. Don't report bugs seen with this client to the jinteki team.

## Test games without clicking (dev only)

With the dev server running (`bin/up`), `bin/dev-game` sets up and drives a
game between two test users, `devcorp` (Corp) and `devrunner` (Runner). They are
created on first use. It talks to the server's nREPL (port 44867) and takes
about a second per call.

    bin/dev-game new                    # fresh System Gateway game, both hands kept
    bin/dev-game scenario run           # ... played up to a live run (Jailbreak on HQ)
    bin/dev-game state                  # short summary: turn, credits, hands, prompts, log
    bin/dev-game act corp credit        # any game command as either side
    bin/dev-game play corp Palisade HQ  # play/install a card from hand by title
    bin/dev-game choose runner HQ       # answer the current prompt
    bin/dev-game tutor runner Jailbreak # dev cheat: move a card from deck to hand
    bin/dev-game end                    # close the dev game

To watch or play in a browser, open

    http://localhost:1042/play?dev-login=devrunner

(or `devcorp`). That logs the browser in as the test user, skips the start
dialog and opens the running game. Client options for that tab can be added,
e.g. `&dev-opts=board-3d,board-3d-playmat=/img/playmats/mat.jpg`. This only
exists in dev builds (`src/cljs/dev/nr.cljs`). One browser can play one side
while `bin/dev-game` plays the other.

`bin/dev-repl '<clojure expr>'` evaluates any expression in the running server,
e.g. `bin/dev-repl "(web.dev-game/summary)"`. The game helpers live in
`dev/src/clj/web/dev_game.clj`.

## Profiling Database Queries

This document gives a short introduction on profiling database queries and
creating appropriate indexes. It is *purposely superficial*, for a deeper
understanding of the topic it is recommended to consult the [official
documentation](https://docs.mongodb.com/manual/core/crud/).

We'll combine two different approaches to identity slow and/or inefficient
queries:

1. Identifying slow queries by measuring their execution times on large
   collections, and
2. Analyzing these queries in a Mongo shell using `explain()`.


### Populating your database with sample data

The following command will create approx. 50k users, 550k decks, 250k game-logs
and 250k messages (taking a couple of minutes and using around 600MB of disk
space):

    $ lein create-sample-data


### Starting `mongod` with profiling enabled

    $ mongod --profile 1 --slowms 20


### Identifying slow queries

You can use `$ lein drop-indexes` to drop all created indexes to test logging
of slow queries. If you then open your local instance in a browser, `mongod`
should output messages like this one:

    2021-02-05T05:51:08.796+0100 I COMMAND  [conn5] command netrunner.messages command: find { find: "messages", filter: { channel: "general" }, sort: { date: -1 }, limit: 100, batchSize: 256 } planSummary: COLLSCAN keysExamined:0 docsExamined:250490 hasSortStage:1 cursorExhausted:1 numYields:1958 nreturned:100 reslen:16007 locks:{ Global: { acquireCount: { r: 3918 } }, Database: { acquireCount: { r: 1959 }, acquireWaitCount: { r: 1 }, timeAcquiringMicros: { r: 10475 } }, Collection: { acquireCount: { r: 1959 } } } protocol:op_query 718ms

`mongod` tells you the command that was slow,

    find {
        find: "messages",
        filter: { channel: "general" },
        sort: { date: -1 },
        limit: 100,
        batchSize: 256
    }

and some details about its plan and execution:

    planSummary: COLLSCAN
    keysExamined:0
    docsExamined:250490
    hasSortStage:1
    cursorExhausted:1
    numYields:1958
    nreturned:100
    reslen:16007

You'll want to look out for `COLLSCAN` and very large numbers in `docsExamined`
compared to the actual number of results (`nreturned`). Both point to a missing
index to support the query. Further details on reading the output can be found
at [Database Profiler
Output](https://docs.mongodb.com/manual/reference/database-profiler/).


### Analyzing the query and creating appropriate indexes

Hop in your nearest `mongo` shell and explain any query you suspect to be
inefficient:

    $ mongo netrunner
    > db.messages.find({ channel: "general" }).sort({ date: -1 }).limit(100).explain()
    {
        "queryPlanner" : {
            [...]
            "winningPlan" : {
                "stage" : "SORT",
                "sortPattern" : {
                    "date" : -1
                },
                "limitAmount" : 100,
                "inputStage" : {
                    "stage" : "SORT_KEY_GENERATOR",
                    "inputStage" : {
                        "stage" : "COLLSCAN",
                        "filter" : {
                            "channel" : {
                                "$eq" : "general"
                            }
                        },
                        "direction" : "forward"
                    }
                }
            },
            [...]
        },
        [...]
    }

This will print a detailed plan of how Mongo will execute this query. In
general, seeing `IXSCAN` is good, `COLLSCAN` is bad. See [Explain
Results](https://docs.mongodb.com/manual/reference/explain-results/) for
details.


### Creating appropriate indexes

Queries should usually have *one* index so support them. While Mongo can use
multiple indexes for one query, it is not recommended to rely on it (see [Index
Intersection](https://docs.mongodb.com/manual/core/index-intersection/) for
details).

In our example above, we can try if an index for `{ channel: 1 }` is sufficient:

    > db.messages.createIndex({ channel: 1 })
    > db.messages.find({ channel: "general" }).sort({ date: -1 }).limit(100).explain()
    {
        "queryPlanner" : {
            [...]
            "winningPlan" : {
                "stage" : "SORT",
                "sortPattern" : {
                        "date" : -1
                },
                "limitAmount" : 100,
                "inputStage" : {
                    "stage" : "SORT_KEY_GENERATOR",
                    "inputStage" : {
                        "stage" : "FETCH",
                        "inputStage" : {
                            "stage" : "IXSCAN",
                            [...]
                        }
                    }
                }
            },
            "rejectedPlans" : [ ]
        },
        [...]
    }

That looks better. The `COLLSCAN` is gone, meaning Mongo can use our index to
fetch matching documens. However, including the `"executionStats"` we will see
that this is still inefficient:

    > db.messages.find({ channel: "general" }).sort({ date: -1 }).limit(100).explain("executionStats")
    {
        [...]
        "executionStats" : {
            "executionSuccess" : true,
            "nReturned" : 100,
            "executionTimeMillis" : 442,
            "totalKeysExamined" : 250490,
            "totalDocsExamined" : 250490,
            [...]
        }
        [...]
    }

Even with our new index, Mongo still has to sort more than 250k documents in
memory, returning only 100 of them. We'll refine our index to `{ channel: 1,
date: -1 }`:

    > db.messages.dropIndex({ channel: 1 })
    > db.messages.createIndex({ channel: 1, date: -1 })
    > db.messages.find({ channel: "general" }).sort({ date: -1 }).limit(100).explain("executionStats")
    {
        [...]
        "executionStats" : {
            "executionSuccess" : true,
            "nReturned" : 100,
            "executionTimeMillis" : 3,
            "totalKeysExamined" : 100,
            "totalDocsExamined" : 100,
            [...]
        }
        [...]
    }

Perfect. The execution time dropped from 442ms to 3ms and we're furthermore not
wasting CPU cycles and memory by sorting huge amounts of data.

You can now add this index to `indexes` in `src/clj/tasks/db.clj`. If there is
already an index for `{ channel: 1 }`, you can replace it with `{ channel: 1,
date: -1 }`, as the latter can also be used for all queries the former can
support. See [Compound
Indexes](https://docs.mongodb.com/manual/core/index-compound/) for details.
