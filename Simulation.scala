import processing.core.PApplet
import scala.collection.mutable.{Set => MSet, Map => MMap}

package physarum {
  /** Trait representing pheromones, objects which can be associated with
    * a concentration */
  trait Pheromone

  /** Case object for the attractant pheromone */
  case object Attract extends Pheromone

  /** Objects which periodically emit some pheromone. */
  trait Odiferous {
    def scent: Map[Pheromone, Double]
  }

  /** A food source, represented as an object which secretes attractant */
  class Food(amount: Double) extends Odiferous {
    def scent: Map[Pheromone, Double] = Map(Attract -> amount)
  }

  /**
    * The state of an instance of [[physarum.Simulation]] is made up of a 2D
    * grid of these cells. The cell is a kind of container representing
    * the state of some discrete chunk of 2D space. It stores the pheromone
    * amounts in the space as well as food sources and plasmodium.
    */
  class Cell {
    /** Mutable set of objects at this location */
    val objects: MSet[Odiferous] = MSet()
    
    /** Mutable map of pheromone levels */
    val pheromones: MMap[Pheromone, Double] = MMap()

    /** We need this while we calculate the new pheromone levels during
      * dissipation. Since each cell's pheromone levels on the nth iteration
      * affect its neighbors' pheromone levels on the (n+1)th iteration, and
      * since we want to update pheromone levels in-place, we have to store
      * the current levels while we update the next levels. */
    val current_pheromones: MMap[Pheromone, Double] = MMap()

    /** Mutable set of neighbors */
    val neighbors: MSet[Cell] = MSet()

    /** Connects this cell to a neighbor. Used when initialize the model. */
    def connect_to(neighbor: Cell) = {
      neighbors.add(neighbor)
      neighbor.neighbors.add(this)
    }

    /* Our underlying model is based on having various pheromone levels through
     * each cell. Pheromone levels will change through the following events
     * which happen each iteration.
     *
     * - Exponential decay. Pheromone levels are multiplied by a constant
     *   0 < beta < 1
     *
     * - Secretion. Objects within a cell, like food, obstacles, or plasmodium
     *   will deposit pheromone in the cell containing them.
     *
     * - Dissipation. This is based on Newton's Law of Cooling, where the
     *   flux (in this case of chemical concentration) is proportional to
     *   the gradient of concentration, with some constant of proportionality
     *   kappa.
     */

    /**
      * Implements an exponential decay on the cell's pheromone levels
      * by multiplying each pheromone level by a decay constant. Called by
      * [[physarum.Simulation]]'s update_pheromone method.
      */
    def decay_pheromone() {
      val decay_constant = 0.8

      /* Iterate over (pheromone, level) pairs, mutating the levels
       * by multiplying them by the decay constant */
      pheromones.foreach({
        case (pheromone, level) => {
          pheromones.update(pheromone, level * decay_constant)
        }
      })
    }

    /** Adds some amount of pheromone to the current mapping. */
    def add_pheromone(pher: Pheromone, amount: Double) =
      pheromones.get(pher) match {
        case None => pheromones.update(pher, amount)
        case Some(existing_amount) => pheromones.update(pher, existing_amount + amount)
      }
    
    def secrete_pheromone() =
      objects.foreach(obj =>
        obj.scent.foreach({
          /* For each object in the cell, find its scent and add it to our
           * mapping. */
          case (pher, amount) => add_pheromone(pher, amount)
        })
      )

    def update_local_pheromone() {
      decay_pheromone()
      secrete_pheromone()
    }

    def save_pheromone() {
      current_pheromones.clear()
      current_pheromones ++= pheromones
    }

    def dissipate_pheromone() {
      val dissipation_constant = 0.5 / 8

      def pheromone_gradient(a: Cell, b: Cell): Map[Pheromone, Double] = {
        val output: MMap[Pheromone, Double] = MMap()
        
        val (a_phers, b_phers) = (a.current_pheromones.keySet,
                                  b.current_pheromones.keySet)

        // Which pheromones these cells have in common
        val shared_pheromones = a_phers & b_phers

        // Which pheromones each of these cells has exclusively
        val (a_exclusive, b_exclusive) = (a_phers &~ b_phers, b_phers &~ a_phers)

        shared_pheromones.foreach(pher =>
          output.update(pher, b.current_pheromones(pher) - a.current_pheromones(pher)))

        /* If a pheromone is in cell A but not in cell B, that is a
         * negative gradient. If a that pheromone is in B but not A, that's
         * a positive gradient. */
        a_exclusive.foreach(pher =>
          output.update(pher, -a.current_pheromones(pher)))

        b_exclusive.foreach(pher =>
          output.update(pher, b.current_pheromones(pher)))

        output.toMap
      }

      /* Compute the pheromone gradient for each neighbor and update pheromone
       * levels in both cells.
       */
      neighbors.foreach(neighbor => {
        val gradient = pheromone_gradient(this, neighbor)
        gradient.foreach({
          case (pher, level) => {
            neighbor.add_pheromone(pher, -dissipation_constant * level)
            add_pheromone(pher, dissipation_constant * level)
          }
        })
      })
    }
  }

  /**
    * A simulation of Physarum polycephalum, or slime mold. Slime molds begin
    * their lives as spores and go into a vegetative state (the plasmodium)
    * which grows to cover food sources.

    * The basic principle of growth behind the plasmodium is that it tends to
    * get larger in the areas that are carrying the most resources. In this
    * way, the slime mold prioritizes tissues that are most immediately
    * useful to it.

    * In this simulation, we will model the utility of a given region in space
    * by leaving chemical markers (or pheromones) to indicate where the slime
    * mold will want to grow.
    */
  class Simulation extends PApplet {
    val (rows, cols) = (10, 10)

    /** The simulation's state is represented by a mutable 2D grid of cells */
    val grid: Array[Array[Cell]] = Array.ofDim(rows, cols)

    /** Inherited from PApplet. Sets up the model and view states for the
      * simulation.
      */
    override def setup() {
      // Set up the model state
      grid.indices.foreach(row =>
        grid(row).indices.foreach(col =>
          grid(row)(col) = new Cell()
        )
      )

      // Connect the cells
      grid.indices.foreach(row =>
        grid(row).indices.foreach(col => {
          /* Calculate the indices of neighbors. Here we list the possible
           * values for the neighbor row and column indices. */
          val row_indices = List(row - 1, row, row + 1)
          val col_indices = List(col - 1, col, col + 1)

          // Now we take a Cartesian product using wonderful monads.
          val neighbor_indices = row_indices.flatMap(r =>
            col_indices.map(c => (r, c)))

          // Filter out the indices that our outside the bounds of our grid.
          neighbor_indices.filter({
            case (row, col) => (row >= 0) && (row < rows) &&
                               (col >= 0) && (col < cols)
          }).foreach({
            // Now connect the cell to its neighbors.
            case (row_index, col_index) =>
              grid(row)(col).connect_to(grid(row_index)(col_index))
          })
        }))
          

      size(1024, 768)
    }

    /** Inherited from PApplet. Draws the state of the simulation. */
    override def draw() {
      update_model()
      background(0x20, 0x20, 0x20)
    }

    def update_model() {
      update_pheromone()
    }

    /**
      * Iterates over all of the cells in the grid, updating their pheromone
      * levels */
    def update_pheromone() {
      // Iterate over each cell in the grid
      grid.foreach(row =>
        row.foreach(cell => {
          // For each cell, first dissipate the pheromone levels already
          // within the cell
          cell.update_local_pheromone()
          cell.save_pheromone()
        })
      )

      grid.foreach(row =>
        row.foreach(cell =>
          cell.dissipate_pheromone()))
    }
  }

  /** A simple class which launches [[physarum.Simulation]]. */
  object Main {
    def main(args: Array[String]) {
      PApplet.main("physarum.Simulation")
    }
  }
}
