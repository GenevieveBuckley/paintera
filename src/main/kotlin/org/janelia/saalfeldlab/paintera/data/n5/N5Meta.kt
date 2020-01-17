package org.janelia.saalfeldlab.paintera.data.n5

import org.janelia.saalfeldlab.n5.DatasetAttributes
import org.janelia.saalfeldlab.n5.N5FSReader
import org.janelia.saalfeldlab.n5.N5Reader
import org.janelia.saalfeldlab.n5.N5Writer
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.invoke.MethodHandles

interface N5Meta {
    @get:Throws(IOException::class)
    val reader: N5Reader

    @Throws(IOException::class)
    @Deprecated("Use property syntax instead", replaceWith = ReplaceWith("reader"))
    fun reader() = reader

    @get:Throws(IOException::class)
    val writer: N5Writer

    @Throws(IOException::class)
    @Deprecated("Use property syntax instead", replaceWith = ReplaceWith("writer"))
    fun writer() = writer

    val dataset: String

    @Deprecated("Use property syntax instead", replaceWith = ReplaceWith("dataset"))
    fun dataset() = dataset

    @get:Throws(IOException::class)
    val datasetAttributes: DatasetAttributes
        get() = reader.getDatasetAttributes(dataset)

    @Throws(IOException::class)
    @Deprecated("Use property syntax instead", replaceWith = ReplaceWith("datasetAttributes"))
    fun datasetAttributes() = datasetAttributes

    companion object {

        val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

        @Throws(ReflectionException::class)
        @JvmStatic
        fun fromReader(reader: N5Reader, dataset: String): N5Meta? {
            if (reader is N5FSReader) {
                return N5FSMeta(reader, dataset)
            }

            if (reader is N5HDF5Reader) {
                return N5HDF5Meta(reader, dataset)
            }

            LOG.debug("Cannot create meta for reader of type {}", reader.javaClass.name)

            return null
        }
    }

}
