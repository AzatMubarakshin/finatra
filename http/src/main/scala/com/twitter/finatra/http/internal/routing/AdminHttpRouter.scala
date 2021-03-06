package com.twitter.finatra.http.internal.routing

import com.twitter.finagle.http.{Method, HttpMuxer}
import com.twitter.finatra.http._
import com.twitter.finatra.http.routing.HttpRouter
import com.twitter.inject.Logging
import com.twitter.server.AdminHttpServer
import com.twitter.server.AdminHttpServer._
import com.twitter.util.lint.{Issue, Category, Rule, GlobalRules}

private[http] object AdminHttpRouter extends Logging {

  /**
   * Adds routes to the TwitterServer HTTP Admin Interface.
   *
   * Constant routes which do not begin with /admin/finatra can be added to the admin index,
   * all other routes cannot. Only constant /GET or /POST routes will be eligible to be added
   * to the admin index.
   *
   * NOTE: beforeRouting = true filters will not be properly evaluated on adminIndexRoutes
   * since the local Muxer in the AdminHttpServer does exact route matching before marshalling
   * to the handler service (where the filter is composed). Thus if this filter defines a route
   * it will not be routed to by the local Muxer. Any beforeRouting = true filters should act
   * only on paths behind /admin/finatra.
   */
  def addAdminRoutes(
    server: HttpServer,
    router: HttpRouter,
    twitterServerAdminRoutes: Seq[AdminHttpServer.Route]
  ): Unit = {
    val allTwitterServerAdminRoutes = twitterServerAdminRoutes.map(_.path).union(HttpMuxer.patterns)
    val duplicates = allTwitterServerAdminRoutes.intersect(router.routesByType.admin.map(_.path))
    if (duplicates.nonEmpty) {
      val message = "The following routes are duplicates of pre-defined TwitterServer admin routes:"
      warn(s"$message \n\t${duplicates.mkString("\n\t")}")
    }

    // Partition routes into admin index routes and admin rich handler routes
    val (adminIndexRoutes, adminRichHandlerRoutes) = router.routesByType.admin.partition { route =>
      // admin index routes cannot start with /admin/finatra/ and must be a constant route
      !route.path.startsWith(HttpRouter.FinatraAdminPrefix) && route.constantRoute
    }

    // Run linting rule for routes
    GlobalRules.get.add(
      Rule(
        Category.Configuration,
        "Non-indexable HTTP Admin Interface Finatra Routes",
        s"""Only constant /GET or /POST routes that DO NOT begin with "${HttpRouter.FinatraAdminPrefix}" can be added to the TwitterServer HTTP Admin Interface index."""
      ) {
        Seq(
          checkIfRoutesDefineRouteIndex(adminRichHandlerRoutes) { _ => true },
          checkIfRoutesDefineRouteIndex(adminIndexRoutes) { !hasAcceptableAdminIndexRouteMethod(_) }
        ).flatten
      }
    )

    // Add constant routes to admin index
    server.addAdminRoutes(
      toAdminHttpServerRoutes(
        adminIndexRoutes, router))

    // Add rich handler for all other routes
    if (adminRichHandlerRoutes.nonEmpty) {
      HttpMuxer.addRichHandler(
        HttpRouter.FinatraAdminPrefix,
        router.services.adminService)
    }
  }

  /* Private */

  /** Check if routes define a RouteIndex but are NOT eligible for TwitterServer HTTP Admin Interface index. */
  private def checkIfRoutesDefineRouteIndex(
    routes: Seq[Route]
  )(predicate: Route => Boolean): Seq[Issue] = {
    routes.filter(route => route.index.isDefined && predicate(route)).map { route =>
      Issue(s""""${route.summary}" specifies a RouteIndex but cannot be added to the index.""")
    }
  }

  /** Allows HTTP methods: GET, POST or AnyMethod (with the assumption that users will only answer GET or POST) */
  private def hasAcceptableAdminIndexRouteMethod(route: Route) = route.method match {
    case Method.Get | Method.Post | AnyMethod => true
    case _ => false
  }

  private def toAdminHttpServerRoutes(
    routes: Seq[Route],
    router: HttpRouter
  ): Seq[AdminHttpServer.Route] = {
    routes.map { route =>
      route.index match {
        case Some(index) =>
          mkRoute(
            path = route.path,
            handler = router.services.adminService,
            alias = if (index.alias.nonEmpty) index.alias else route.path,
            group = Some(index.group),
            includeInIndex = hasAcceptableAdminIndexRouteMethod(route))
        case _ =>
          mkRoute(
            path = route.path,
            handler = router.services.adminService,
            alias = route.path,
            group = None,
            includeInIndex = false)
      }
    }
  }
}
