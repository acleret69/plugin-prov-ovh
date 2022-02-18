/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.ovh.catalog.vm;

import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractQuoteVmOs;
import org.ligoj.app.plugin.prov.model.AbstractTermPriceVmOs;
import org.ligoj.app.plugin.prov.ovh.catalog.AbsractLocalContext;
import org.ligoj.app.plugin.prov.ovh.catalog.vm.instance.AbstractCsvForBeanEc2;
import org.ligoj.app.plugin.prov.ovh.catalog.vm.instance.AbstractOvhVmOsPrice;

/**
 * The compute part of AWS catalog import.
 *
 * @param <T> The instance type's type.
 * @param <P> The price's type.
 * @param <C> The JSON price type.
 * @param <Q> The quote type.
 * @param <X> The context type.
 * @param <R> The CSV bean reader type.
 */
public abstract class AbstractAwsPriceImportVmOs<T extends AbstractInstanceType, P extends AbstractTermPriceVmOs<T>, C extends AbstractOvhVmOsPrice, Q extends AbstractQuoteVmOs<P>, X extends AbsractLocalContext<T, P, C, Q>, R extends AbstractCsvForBeanEc2<C>>
		extends AbstractAwsPriceImportVm<T, P, C, Q, X, R> {

	@Override
	protected void copy(final C csv, final P p) {
		p.setOs(toVmOs(csv.getOs()));
	}

	@Override
	protected void copySavingsPlan(final P odPrice, final P p) {
		super.copySavingsPlan(odPrice, p);
		p.setOs(odPrice.getOs());
	}

	@Override
	protected boolean isEnabled(final X context, final C csv) {
		return super.isEnabled(context, csv) && isEnabledOs(context, csv.getOs());
	}

}
