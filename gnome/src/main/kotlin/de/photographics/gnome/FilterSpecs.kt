package de.photographics.gnome

// Filterbeschreibungen liegen im Core (de.photographics.core.filters.FilterCatalog), gemeinsam mit Android.
typealias FilterSpec = de.photographics.core.filters.FilterSpec
typealias FilterParam = de.photographics.core.filters.FilterParam
typealias ParamKind = de.photographics.core.filters.ParamKind
typealias AdjustmentFilter = de.photographics.core.filters.AdjustmentFilter

/** Filter-Menü, gruppiert wie in Photoshop. */
val FilterSpecs: List<FilterSpec> get() = de.photographics.core.filters.FilterCatalog.specs
