#ifndef MEMTRACK_H
#define MEMTRACK_H

#include "c.h"

#define PG_MEM_TRACKER_INIT \
	{ \
		0, 0, 0, 0 \
	}

/*
 * Tracking memory consumption for both PG backend and pggate tcmalloc acutal
 * heap consumption.
 * Global accessible in one PG backend process.
 */
typedef struct YbPgMemTracker
{
	Size cur_mem_bytes;
	Size backend_max_mem_bytes;
	Size stmt_max_mem_bytes;
	Size stmt_max_mem_base_bytes;
} YbPgMemTracker;

extern YbPgMemTracker PgMemTracker;

/*
 * Update current memory usage in MemTracker, when there is no PG
 * memory allocation activities. This is currently supposed to be
 * used by the MemTracker in pggate as a callback.
 */
extern void YbPgMemUpdateMax();

/*
 * Add memory consumption to PgMemTracker in bytes.
 * sz can be negative. In this case, the max values are not
 * updated.
 */
extern void YbPgMemAddConsumption(const Size sz);

/*
 * Substract the sz bytes from PgMemTracker. It doesn't update the maximum
 * values for the backend and stmt.
 */
extern void YbPgMemSubConsumption(const Size sz);

/*
 * Reset the PgMemTracker's stmt fields and make it ready to
 * track peak memory usage for a new statement.
 */
extern void YbPgMemResetStmtConsumption();

#endif