/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 委托模式
 * WebHandler委托给GlobalFilter执行, 然后返回
 * WebHandler that delegates to a chain of {@link GlobalFilter} instances and
 * {@link GatewayFilterFactory} instances then to the target {@link WebHandler}.
 *
 * @author Rossen Stoyanchev
 * @author Spencer Gibb
 * @since 0.1
 */
public class FilteringWebHandler implements WebHandler {

	protected static final Log logger = LogFactory.getLog(FilteringWebHandler.class);

	// 全局过滤器
	private final List<GatewayFilter> globalFilters;

	public FilteringWebHandler(List<GlobalFilter> globalFilters) {
		this.globalFilters = loadFilters(globalFilters);
	}

	/**
	 * 包装全局过滤器
	 * @param filters
	 * @return
	 */
	private static List<GatewayFilter> loadFilters(List<GlobalFilter> filters) {
		return filters.stream().map(filter -> {
			// 包装成网关过滤器
			GatewayFilterAdapter gatewayFilter = new GatewayFilterAdapter(filter);
			if (filter instanceof Ordered) {
				int order = ((Ordered) filter).getOrder();
				// 包装成可排序网关过滤器
				return new OrderedGatewayFilter(gatewayFilter, order);
			}
			return gatewayFilter;
		}).collect(Collectors.toList());
	}

	/*
	 * TODO: relocate @EventListener(RefreshRoutesEvent.class) void handleRefresh() {
	 * this.combinedFiltersForRoute.clear();
	 */

	@Override
	public Mono<Void> handle(ServerWebExchange exchange) {
		/**
		 * 从header中获取路由信息
		 * {@link RoutePredicateHandlerMapping#getHandlerInternal(ServerWebExchange)}
		 * */
		Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
		// 获取网关过滤器
		List<GatewayFilter> gatewayFilters = route.getFilters();
		// 全局过滤器+路由配置的过滤器+网关过滤器
		List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);
		combined.addAll(gatewayFilters);
		// 根据Ordered排序
		AnnotationAwareOrderComparator.sort(combined);

		if (logger.isDebugEnabled()) {
			logger.debug("Sorted gatewayFilterFactories: " + combined);
		}
		// 创建过滤器链表进行链式调用
		return new DefaultGatewayFilterChain(combined).filter(exchange);
	}

	/**
	 * 默认网关过滤器链实现
	 *
	 */
	private static class DefaultGatewayFilterChain implements GatewayFilterChain {

		// 当前过滤器在集合中索引
		private final int index;

		// 过滤器集合
		private final List<GatewayFilter> filters;

		DefaultGatewayFilterChain(List<GatewayFilter> filters) {
			this.filters = filters;
			this.index = 0;
		}

		/**
		 * 构建当前执行的过滤器链
		 * @param parent 上个过滤器链
		 * @param index 当前过滤器的索引
		 */
		private DefaultGatewayFilterChain(DefaultGatewayFilterChain parent, int index) {
			this.filters = parent.getFilters();
			this.index = index;
		}

		public List<GatewayFilter> getFilters() {
			return filters;
		}

		@Override
		public Mono<Void> filter(ServerWebExchange exchange) {
			return Mono.defer(() -> {
				if (this.index < filters.size()) {
					GatewayFilter filter = filters.get(this.index);
					DefaultGatewayFilterChain chain = new DefaultGatewayFilterChain(this,
							this.index + 1);
					// 执行过滤器filter
					return filter.filter(exchange, chain);
				}
				else {
					// 执行完成
					return Mono.empty(); // complete
				}
			});
		}

	}

	private static class GatewayFilterAdapter implements GatewayFilter {

		private final GlobalFilter delegate;

		GatewayFilterAdapter(GlobalFilter delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
			return this.delegate.filter(exchange, chain);
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("GatewayFilterAdapter{");
			sb.append("delegate=").append(delegate);
			sb.append('}');
			return sb.toString();
		}

	}

}
